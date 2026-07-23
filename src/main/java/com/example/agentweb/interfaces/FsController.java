package com.example.agentweb.interfaces;

import com.example.agentweb.app.UploadFileStorage;
import com.example.agentweb.app.UploadPicStorage;
import com.example.agentweb.app.setting.WorkspaceSettingsQueryService;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import com.example.agentweb.interfaces.dto.SuccessResponse;
import com.example.agentweb.interfaces.dto.UploadResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side file browser with upload / download / delete.
 * Allowed roots are read from runtime workspace settings; agent.fs.roots only provides the seed.
 * @author zhourui(V33215020)
 */
@RestController
@RequestMapping(path = "/api/fs")
public class FsController {

    /** 聊天图片上传大小上限:1 MB。 */
    private static final long MAX_IMAGE_UPLOAD_BYTES = 1024L * 1024L;

    /** 聊天附件上传大小上限:5 MB。 */
    private static final long MAX_FILE_UPLOAD_BYTES = 5L * 1024L * 1024L;

    private final WorkspaceSettingsQueryService workspaceSettingsQueryService;
    private final WorkspacePathPolicy pathPolicy;
    private final UploadPicStorage uploadPicStore;
    private final UploadFileStorage uploadFileStore;

    public FsController(WorkspaceSettingsQueryService workspaceSettingsQueryService,
                        UploadPicStorage uploadPicStore,
                        UploadFileStorage uploadFileStore,
                        WorkspacePathPolicy pathPolicy) {
        this.workspaceSettingsQueryService = workspaceSettingsQueryService;
        this.uploadPicStore = uploadPicStore;
        this.uploadFileStore = uploadFileStore;
        this.pathPolicy = pathPolicy;
    }

    @GetMapping(value = "/roots", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> roots() {
        return workspaceSettingsQueryService.get().effectiveWorkspaceRoots();
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list(@RequestParam(value = "path", required = false) String path) {
        String requested = StringUtils.hasText(path) ? path : defaultRoot();
        String base = pathPolicy.requireExistingDirectory(requested);
        File dir = new File(base);

        File[] children = dir.listFiles();
        List<Map<String, Object>> dirs = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (children != null) {
            for (File f : children) {
                if (Files.isSymbolicLink(f.toPath())) {
                    continue;
                }
                Map<String, Object> m = new HashMap<String, Object>(8);
                m.put("name", f.getName());
                m.put("path", f.getAbsolutePath());
                m.put("dir", f.isDirectory());
                m.put("size", f.length());
                m.put("lastModified", f.lastModified());
                if (f.isDirectory()) {
                    dirs.add(m);
                } else {
                    files.add(m);
                }
            }
        }

        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(dirs.size() + files.size() + 1);
        // Parent link
        Path p = Paths.get(base);
        Path parent = p.getParent();
        if (parent != null && pathPolicy.isExistingPathAllowed(parent.toString())) {
            Map<String, Object> up = new HashMap<String, Object>(4);
            up.put("name", "..");
            up.put("path", parent.toString());
            up.put("dir", Boolean.TRUE);
            out.add(up);
        }
        out.addAll(dirs);
        out.addAll(files);
        return out;
    }

    @PostMapping(value = "/upload", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse upload(@RequestParam("path") String path,
                                 @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > MAX_FILE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件大小不能超过 5MB");
        }
        String targetDir = pathPolicy.prepareUploadDirectory(path);
        Files.createDirectories(Paths.get(targetDir));
        targetDir = pathPolicy.prepareUploadDirectory(targetDir);
        String originalName = file.getOriginalFilename();
        requireSafeFileName(originalName);
        Path targetFile = Paths.get(targetDir, originalName);
        if (Files.exists(targetFile)) {
            throw new IllegalStateException("File already exists: " + originalName);
        }
        Files.copy(file.getInputStream(), targetFile);

        return new UploadResponse(true, targetFile.toString(), file.getSize());
    }

    /**
     * 上传聊天图片:落到「工作空间下的 upload_pic 目录」,返回服务器绝对路径,供前端拼进消息文本。
     *
     * <p>{@code sessionId} 非空时按会话归集到 {@code upload_pic/<sessionId>/} 子目录,
     * 删除会话时可一并清理;不传 sessionId 时回退到扁平结构,兼容历史调用方。</p>
     *
     * <p>上传目录须落在 {@code agent.fs.roots} 允许的根之内,与 {@code list/download/delete} 同一安全边界。</p>
     */
    @PostMapping(value = "/upload-image", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse uploadImage(@RequestParam("path") String path,
                                      @RequestParam(value = "sessionId", required = false) String sessionId,
                                      @RequestParam("file") MultipartFile file) throws IOException {
        String workingDir = pathPolicy.requireExistingDirectory(path);
        if (file.getSize() > MAX_IMAGE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("图片大小不能超过 1MB");
        }
        String saved = uploadPicStore.save(workingDir, sessionId, file.getBytes());
        return new UploadResponse(true, saved, file.getSize());
    }

    /**
     * 上传聊天附件(文本类:log/txt/json/csv/...):落到 {@code <workingDir>/upload_file/<sessionId>/},
     * 返回服务器绝对路径,供前端拼进消息文本。
     *
     * <p>5MB 上限、扩展名白名单、二进制嗅探拒绝、原文件名 sanitize 等校验由 {@link UploadFileStorage} 实现完成。</p>
     */
    @PostMapping(value = "/upload-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse uploadChatFile(@RequestParam("path") String path,
                                          @RequestParam(value = "sessionId", required = false) String sessionId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        String workingDir = pathPolicy.requireExistingDirectory(path);
        if (file.getSize() > MAX_FILE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件大小不能超过 5MB");
        }
        String saved = uploadFileStore.save(workingDir, sessionId, file.getOriginalFilename(), file.getBytes());
        return new UploadResponse(true, saved, file.getSize());
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) {
        String filePath = pathPolicy.requireExistingFile(path);
        File file = new File(filePath);
        Resource resource = new FileSystemResource(file);
        String encodedName;
        try {
            encodedName = java.net.URLEncoder.encode(file.getName(), "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            encodedName = file.getName();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(resource);
    }

    /**
     * 内联返回图片字节,供前端 {@code <img>} 直接显示对话里上传的图片。
     *
     * <p>与 {@code download} 的区别:本接口按扩展名返回 {@code image/*} MIME 且
     * {@code Content-Disposition: inline}(不触发下载);非图片扩展名直接拒绝。
     * 路径同样受 {@code agent.fs.roots} 约束。</p>
     */
    @GetMapping("/image")
    public ResponseEntity<Resource> image(@RequestParam("path") String path) {
        String filePath = pathPolicy.requireExistingFile(path);
        File file = new File(filePath);
        MediaType mediaType = imageMediaType(file.getName());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(file));
    }

    /** 按扩展名推断图片 MIME;非图片扩展名拒绝,避免把任意文件当图片内联返回。 */
    private MediaType imageMediaType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        throw new IllegalArgumentException("Not an image file: " + fileName);
    }

    @DeleteMapping(value = "/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public SuccessResponse delete(@RequestParam("path") String path) throws IOException {
        String filePath = pathPolicy.requireExistingFile(path);
        File file = new File(filePath);
        Files.delete(file.toPath());

        return new SuccessResponse(true);
    }

    private String defaultRoot() {
        return workspaceSettingsQueryService.get().getDefaultWorkspace();
    }

    private void requireSafeFileName(String fileName) {
        if (!StringUtils.hasText(fileName) || fileName.length() > 255
                || ".".equals(fileName) || "..".equals(fileName)
                || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("File name is invalid");
        }
        for (int i = 0; i < fileName.length(); i++) {
            if (Character.isISOControl(fileName.charAt(i))) {
                throw new IllegalArgumentException("File name is invalid");
            }
        }
    }

}
