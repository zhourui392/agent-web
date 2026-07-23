package com.example.agentweb.infra.git;

import com.example.agentweb.app.worktree.GitRepoEntry;
import com.example.agentweb.app.worktree.WorktreeFileGateway;
import com.example.agentweb.app.worktree.WorktreeLeaves;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

/**
 * {@link WorktreeFileGateway} 的本地文件系统实现。
 *
 * <p>承载目录遍历(限深、跳过隐藏目录)、symlink / NTFS junction 判定与创建、递归删除等
 * FS 副作用, 含 Windows 兼容分支。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
public class LocalWorktreeFileGateway implements WorktreeFileGateway {

    private static final String CURRENT_DIR_PREFIX = ".";
    private static final String GIT_DIR = ".git";
    /**
     * Depth relative to scan root: root=0, direct child=1. A value of 4
     * lets us find repos at workspace/a/b/c/repo.
     */
    private static final int MAX_SCAN_DEPTH = 4;

    @Override
    public List<GitRepoEntry> collectGitRepos(Path workspace) throws IOException {
        List<GitRepoEntry> repos = new ArrayList<>();
        Files.walkFileTree(workspace, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                MAX_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(workspace)) {
                    return FileVisitResult.CONTINUE;
                }
                // Skip hidden dirs (.git, .worktrees, .idea, ...).
                if (dir.getFileName().toString().startsWith(CURRENT_DIR_PREFIX)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.exists(dir.resolve(GIT_DIR))) {
                    repos.add(new GitRepoEntry(dir.toFile(), workspace.relativize(dir)));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        repos.sort(Comparator.comparing(r -> r.relativePath().toString()));
        return repos;
    }

    @Override
    public WorktreeLeaves classifyLeaves(Path base) throws IOException {
        List<Path> links = new ArrayList<>();
        List<Path> realWorktrees = new ArrayList<>();
        Files.walkFileTree(base, EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                MAX_SCAN_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(base)) {
                    return FileVisitResult.CONTINUE;
                }
                // NTFS junctions masquerade as regular directories to walkFileTree;
                // detect them here so we don't descend into the target checkout.
                if (isDirectoryLink(dir)) {
                    links.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (Files.exists(dir.resolve(GIT_DIR))) {
                    realWorktrees.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) {
                    links.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new WorktreeLeaves(links, realWorktrees);
    }

    @Override
    public boolean hasConfiguredRemote(File repoDir) {
        Path gitPath = repoDir.toPath().resolve(GIT_DIR);
        if (!Files.isDirectory(gitPath)) {
            return true;
        }
        Path config = gitPath.resolve("config");
        if (!Files.isRegularFile(config)) {
            return true;
        }
        try {
            return Files.readAllLines(config).stream()
                    .anyMatch(line -> line.trim().startsWith("[remote "));
        } catch (IOException ex) {
            return true;
        }
    }

    @Override
    public void createDirectories(Path dir) throws IOException {
        Files.createDirectories(dir);
    }

    @Override
    public boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    @Override
    public boolean exists(Path path) {
        return Files.exists(path);
    }

    @Override
    public boolean existsNoFollowLinks(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void delete(Path path) throws IOException {
        Files.delete(path);
    }

    @Override
    public void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    @Override
    public void copyFileIfPresent(Path sourceFile, Path targetDir) throws IOException {
        if (!Files.isRegularFile(sourceFile)) {
            return;
        }
        Files.copy(sourceFile, targetDir.resolve(sourceFile.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    @Override
    public void createDirectoryLink(Path link, Path target) throws IOException, InterruptedException {
        if (isWindows()) {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                    link.toString(), target.toString());
            pb.directory(link.getParent().toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (p.waitFor() != 0) {
                throw new IOException("Failed to create junction: " + output.toString().trim());
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    /**
     * Check whether a path is a directory link (symlink or NTFS junction).
     */
    private boolean isDirectoryLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        // Junction points are not detected by isSymbolicLink on Windows
        if (isWindows() && Files.isDirectory(path)) {
            try {
                Path parent = path.toAbsolutePath().getParent();
                if (parent == null) {
                    return false;
                }
                // 父目录规范化后拼回叶子名 = path 若为普通目录时应有的真实路径；
                // toRealPath 会跟随 junction，二者不同即说明 path 是 junction。
                // 不能直接拿 path.toAbsolutePath().normalize() 比较：toRealPath 会把
                // 8.3 短名（如 V33215~1）展开为长名而 normalize 不会，会令普通目录被误判为链接。
                Path expectedReal = parent.toRealPath().resolve(path.getFileName());
                return !path.toRealPath().equals(expectedReal);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
