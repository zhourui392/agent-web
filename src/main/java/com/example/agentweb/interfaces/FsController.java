package com.example.agentweb.interfaces;

import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Server-side file browser. Only directories are listed.
 * Configure allowed roots via application properties: agent.fs.roots
 */
@RestController
@RequestMapping(path = "/api/fs", produces = MediaType.APPLICATION_JSON_VALUE)
public class FsController {

    private final List<String> roots;

    public FsController(com.example.agentweb.infra.FsProperties fsProps) {
        List<String> configured = fsProps.getRoots();
        // Default to "/" if not configured (Linux); can be narrowed via config.
        this.roots = configured == null || configured.isEmpty() ? Collections.singletonList("/") : configured;
    }

    @GetMapping("/roots")
    public List<String> roots() {
        return roots;
    }

    @GetMapping("/list")
    public List<Map<String, Object>> list(@RequestParam(value = "path", required = false) String path) {
        String base = (StringUtils.hasText(path)) ? sanitize(path) : roots.get(0);
        if (!isUnderRoots(base)) {
            throw new IllegalArgumentException("Path out of allowed roots: " + base);
        }
        File dir = new File(base);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalArgumentException("Not a directory: " + base);
        }
        File[] children = dir.listFiles(File::isDirectory);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (children != null) {
            for (File f : children) {
                Map<String, Object> m = new HashMap<String, Object>(4);
                m.put("name", f.getName());
                m.put("path", f.getAbsolutePath());
                m.put("dir", Boolean.TRUE);
                out.add(m);
            }
        }
        // Add parent link if inside a root
        Path p = Paths.get(base).normalize();
        Path parent = p.getParent();
        if (parent != null && isUnderRoots(parent.toString())) {
            Map<String, Object> up = new HashMap<String, Object>(4);
            up.put("name", "..");
            up.put("path", parent.toString());
            up.put("dir", Boolean.TRUE);
            out.add(0, up);
        }
        return out;
    }

    private String sanitize(String p) {
        return Paths.get(p).normalize().toString();
    }

    private boolean isUnderRoots(String p) {
        String np = Paths.get(p).normalize().toString();
        for (String r : roots) {
            String nr = Paths.get(r).normalize().toString();
            if (np.equals(nr) || np.startsWith(nr + File.separator)) {
                return true;
            }
        }
        return false;
    }
}
