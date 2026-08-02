package com.example.agentweb.infra.runtime;

import lombok.Getter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Runtime 临时目录的幂等清理器。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCleanup {

    public CleanupResult cleanup(Path executionRoot) {
        boolean directoryDeleted = deleteRecursively(executionRoot);
        return new CleanupResult(directoryDeleted);
    }

    public boolean deleteRecursively(Path root) {
        if (root == null || Files.notExists(root)) {
            return true;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * 不含路径的清理结果。
     */
    @Getter
    public static final class CleanupResult {

        private final boolean temporaryDirectoryDeleted;

        private CleanupResult(boolean temporaryDirectoryDeleted) {
            this.temporaryDirectoryDeleted = temporaryDirectoryDeleted;
        }

        public boolean isSuccessful() {
            return temporaryDirectoryDeleted;
        }
    }
}
