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
 */
@RestController
@RequestMapping(path = "/api/fs")
public class FsController {

    private final List<String> roots;

    public FsController(com.example.agentweb.infra.FsProperties fsProps) {
        List<String> configured = fsProps.getRoots();
        this.roots = configured == null || configured.isEmpty() ? Collections.singletonList("/") : configured;
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
        assertUnderHome(targetDir);
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

    private boolean isUnderRoots(String p) {
        String np = Paths.get(p).normalize().toString();
        for (String r : roots) {
            String nr = Paths.get(r).normalize().toString();
            String prefix = nr.endsWith(File.separator) ? nr : nr + File.separator;
            if (np.equals(nr) || np.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void assertUnderHome(String p) {
        String home = System.getProperty("user.home");
        String np = Paths.get(p).normalize().toString();
        String prefix = home.endsWith(File.separator) ? home : home + File.separator;
        if (!np.equals(home) && !np.startsWith(prefix)) {
            throw new IllegalArgumentException("Upload path must be under home directory: " + home);
        }
    }
}
