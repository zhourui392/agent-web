package com.example.agentweb.infra;

import com.example.agentweb.app.setting.WorkspaceSettingsQueryService;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link Path#toRealPath(LinkOption...)} 的工作空间路径边界。
 *
 * <p>已存在路径会跟随符号链接后再做根目录比较。待创建目录则从最近的已存在父级取真实路径，
 * 防止通过根内符号链接逃逸到根外。未配置根目录时 fail-closed。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class RealPathWorkspacePolicy implements WorkspacePathPolicy {

    private final WorkspaceSettingsQueryService workspaceSettingsQueryService;

    public RealPathWorkspacePolicy(WorkspaceSettingsQueryService workspaceSettingsQueryService) {
        this.workspaceSettingsQueryService = workspaceSettingsQueryService;
    }

    @Override
    public String requireExistingDirectory(String path) {
        Path real = requireExisting(path, workspaceRoots());
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("Not a directory: " + path);
        }
        return real.toString();
    }

    @Override
    public String requireExistingFile(String path) {
        Path real = requireExisting(path, workspaceRoots());
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Not a file: " + path);
        }
        return real.toString();
    }

    @Override
    public String prepareWorkspaceDirectory(String path) {
        return prepare(path, workspaceRoots()).toString();
    }

    @Override
    public String prepareUploadDirectory(String path) {
        List<Path> workspaceRoots = workspaceRoots();
        List<Path> uploadRoots = uploadRoots();
        List<Path> allowed = new ArrayList<>(workspaceRoots.size() + uploadRoots.size());
        allowed.addAll(workspaceRoots);
        allowed.addAll(uploadRoots);
        return prepare(path, allowed).toString();
    }

    @Override
    public boolean isExistingPathAllowed(String path) {
        try {
            requireExisting(path, workspaceRoots());
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private Path requireExisting(String rawPath, List<Path> allowedRoots) {
        Path candidate = absolutePath(rawPath);
        try {
            Path real = candidate.toRealPath();
            requireUnderRoots(real, allowedRoots, rawPath);
            return real;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Path not found: " + rawPath, ex);
        }
    }

    private Path prepare(String rawPath, List<Path> allowedRoots) {
        Path candidate = absolutePath(rawPath);
        if (Files.exists(candidate) && !Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("Not a directory: " + rawPath);
        }
        Path ancestor = candidate;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new IllegalArgumentException("Path has no existing ancestor: " + rawPath);
        }
        try {
            Path realAncestor = ancestor.toRealPath();
            Path allowedRoot = findContainingRoot(realAncestor, allowedRoots);
            if (allowedRoot == null) {
                throw new IllegalArgumentException("Path out of allowed roots: " + rawPath);
            }
            Path suffix = ancestor.relativize(candidate);
            Path prepared = realAncestor.resolve(suffix).normalize();
            if (!prepared.startsWith(allowedRoot)) {
                throw new IllegalArgumentException("Path out of allowed roots: " + rawPath);
            }
            return prepared;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Cannot resolve path: " + rawPath, ex);
        }
    }

    private void requireUnderRoots(Path realPath, List<Path> roots, String rawPath) {
        if (findContainingRoot(realPath, roots) == null) {
            throw new IllegalArgumentException("Path out of allowed roots: " + rawPath);
        }
    }

    private Path findContainingRoot(Path realPath, List<Path> roots) {
        for (Path root : roots) {
            try {
                Path realRoot = root.toRealPath();
                if (realPath.equals(realRoot) || realPath.startsWith(realRoot)) {
                    return realRoot;
                }
            } catch (IOException ignored) {
                // 不存在/无权访问的配置根不具备授权能力。
            }
        }
        return null;
    }

    private Path absolutePath(String rawPath) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            throw new IllegalArgumentException("Path is empty");
        }
        Path path = Paths.get(rawPath.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + rawPath);
        }
        return path.normalize();
    }

    private static List<Path> absoluteRoots(List<String> configuredRoots) {
        List<Path> result = new ArrayList<>();
        if (configuredRoots == null) {
            return result;
        }
        for (String root : configuredRoots) {
            if (root != null && !root.trim().isEmpty()) {
                Path path = Paths.get(root.trim());
                if (!path.isAbsolute()) {
                    throw new IllegalArgumentException("Allowed root must be absolute: " + root);
                }
                result.add(path.normalize());
            }
        }
        return result;
    }

    private List<Path> workspaceRoots() {
        return absoluteRoots(workspaceSettingsQueryService.get().getWorkspaceRoots());
    }

    private List<Path> uploadRoots() {
        return absoluteRoots(workspaceSettingsQueryService.get().getUploadRoots());
    }
}
