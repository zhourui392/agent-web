package com.example.agentweb.interfaces;

import com.example.agentweb.interfaces.dto.SuccessResponse;
import com.example.agentweb.interfaces.dto.UploadResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Server-side file browser with upload / download / delete.
 * Configure allowed roots via application properties: agent.fs.roots
 * @author zhourui(V33215020)
 */
@RestController
@RequestMapping(path = "/api/fs")
public class FsController {

    /** 聊天图片上传大小上限:1 MB。 */
    private static final long MAX_IMAGE_UPLOAD_BYTES = 1024L * 1024L;

    /** 聊天附件上传大小上限:5 MB。 */
    private static final long MAX_FILE_UPLOAD_BYTES = 5L * 1024L * 1024L;

    private final List<String> roots;
    private final List<String> uploadRoots;
    private final com.example.agentweb.infra.UploadPicStore uploadPicStore;
    private final com.example.agentweb.infra.UploadFileStore uploadFileStore;

    public FsController(com.example.agentweb.infra.FsProperties fsProps,
                        com.example.agentweb.infra.UploadPicStore uploadPicStore,
                        com.example.agentweb.infra.UploadFileStore uploadFileStore) {
        List<String> configured = fsProps.getRoots();
        this.roots = configured == null || configured.isEmpty() ? Collections.singletonList("/") : configured;
        List<String> configuredUpload = fsProps.getUploadRoots();
        this.uploadRoots = configuredUpload == null ? Collections.<String>emptyList() : configuredUpload;
        this.uploadPicStore = uploadPicStore;
        this.uploadFileStore = uploadFileStore;
    }

    @GetMapping(value = "/roots", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> roots() {
        return roots;
    }

    @GetMapping(value = "/list", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> list(@RequestParam(value = "path", required = false) String path) {
        String base = (StringUtils.hasText(path)) ? sanitize(path) : roots.get(0);
        assertUnderRoots(base);
        File dir = new File(base);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + base);
        }

        File[] children = dir.listFiles();
        List<Map<String, Object>> dirs = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> files = new ArrayList<Map<String, Object>>();
        if (children != null) {
            for (File f : children) {
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
        Path p = Paths.get(base).normalize();
        Path parent = p.getParent();
        if (parent != null && isUnderRoots(parent.toString())) {
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
        String targetDir = sanitize(path);
        assertUploadAllowed(targetDir);
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + targetDir);
        }
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException("File name is empty");
        }
        Path targetFile = Paths.get(targetDir, originalName);
        Files.copy(file.getInputStream(), targetFile, StandardCopyOption.REPLACE_EXISTING);

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
        String workingDir = sanitize(path);
        assertUnderRoots(workingDir);
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
     * <p>5MB 上限、扩展名白名单、二进制嗅探拒绝、原文件名 sanitize 等校验全部在 {@link com.example.agentweb.infra.UploadFileStore} 中完成。</p>
     */
    @PostMapping(value = "/upload-file", produces = MediaType.APPLICATION_JSON_VALUE)
    public UploadResponse uploadChatFile(@RequestParam("path") String path,
                                          @RequestParam(value = "sessionId", required = false) String sessionId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        String workingDir = sanitize(path);
        assertUnderRoots(workingDir);
        if (file.getSize() > MAX_FILE_UPLOAD_BYTES) {
            throw new IllegalArgumentException("文件大小不能超过 5MB");
        }
        String saved = uploadFileStore.save(workingDir, sessionId, file.getOriginalFilename(), file.getBytes());
        return new UploadResponse(true, saved, file.getSize());
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") String path) {
        String filePath = sanitize(path);
        assertUnderRoots(filePath);
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Not a file: " + filePath);
        }
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
        String filePath = sanitize(path);
        assertUnderRoots(filePath);
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("Not a file: " + filePath);
        }
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
        String filePath = sanitize(path);
        assertUnderRoots(filePath);
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("Path not found: " + filePath);
        }
        if (file.isDirectory()) {
            throw new IllegalArgumentException("Cannot delete directory via this API: " + filePath);
        }
        Files.delete(file.toPath());

        return new SuccessResponse(true);
    }

    private String sanitize(String p) {
        return Paths.get(p).normalize().toString();
    }

    private void assertUnderRoots(String p) {
        if (!isUnderRoots(p)) {
            throw new IllegalArgumentException("Path out of allowed roots: " + p);
        }
    }

    /**
     * upload 专用校验:在常规 roots 之外额外放行 agent.fs.upload-roots。
     * upload-roots 只对写入生效,download/delete/list 仍只认 roots,
     * 因此可往该目录写文件,但目录内容不会被 download 接口泄露。
     */
    private void assertUploadAllowed(String p) {
        if (isUnderAny(p, roots) || isUnderAny(p, uploadRoots)) {
            return;
        }
        throw new IllegalArgumentException("Path out of allowed upload roots: " + p);
    }

    private boolean isUnderRoots(String p) {
        return isUnderAny(p, roots);
    }

    private boolean isUnderAny(String p, List<String> rootList) {
        String np = Paths.get(p).normalize().toString();
        for (String r : rootList) {
            String nr = Paths.get(r).normalize().toString();
            String prefix = nr.endsWith(File.separator) ? nr : nr + File.separator;
            if (np.equals(nr) || np.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
