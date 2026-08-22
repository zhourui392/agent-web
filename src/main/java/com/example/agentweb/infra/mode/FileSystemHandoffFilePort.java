package com.example.agentweb.infra.mode;

import com.example.agentweb.app.mode.HandoffFilePort;
import com.example.agentweb.infra.workspace.FileSystemWorkspaceHandoffGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * 交接文件的文件系统适配：写入 {@code <workingDir>/.workbench/handoff/<handoffId>.md}。
 *
 * <p>沿用 Workbench 既有目录约定；写前保障 {@code .gitignore} 条目，
 * 文件权限 600（Windows 由运行账户边界承担）。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Component
@Slf4j
public class FileSystemHandoffFilePort implements HandoffFilePort {

    private static final String HANDOFF_DIR = ".workbench/handoff";
    private static final String FILE_SUFFIX = ".md";
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    private final FileSystemWorkspaceHandoffGuard handoffGuard;

    public FileSystemHandoffFilePort(FileSystemWorkspaceHandoffGuard handoffGuard) {
        this.handoffGuard = handoffGuard;
    }

    @Override
    public String export(String workingDir, String handoffId, String content) {
        Path dir = Path.of(workingDir, HANDOFF_DIR);
        Path file = dir.resolve(handoffId + FILE_SUFFIX);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            secure(file);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "handoff file export failed: " + file, failure);
        }
        handoffGuard.ensureHandoffIgnored(Path.of(workingDir));
        log.info("handoff-file-exported path={} bytes={}",
                file, content == null ? 0 : content.length());
        return HANDOFF_DIR + "/" + handoffId + FILE_SUFFIX;
    }

    @Override
    public String readContent(String workingDir, String relativePath) {
        Path file = resolveSafely(workingDir, relativePath);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.warn("handoff-file-read-failed path={} reason={}", file, failure.getMessage());
            return null;
        }
    }

    @Override
    public void deleteQuietly(String workingDir, String relativePath) {
        Path file = resolveSafely(workingDir, relativePath);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
            log.info("handoff-file-compensated-deleted path={}", file);
        } catch (IOException failure) {
            log.warn("handoff-file-compensate-failed path={} reason={}",
                    file, failure.getMessage());
        }
    }

    /** 目录穿越防御：解析后必须仍在工作目录内。 */
    private static Path resolveSafely(String workingDir, String relativePath) {
        if (workingDir == null || relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = Path.of(workingDir);
        Path resolved = root.resolve(relativePath).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static void secure(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows 权限由运行服务账户边界承担。
        }
    }
}
