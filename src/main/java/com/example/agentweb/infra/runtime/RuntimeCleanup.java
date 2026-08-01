package com.example.agentweb.infra.runtime;

import lombok.Getter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Runtime 临时目录和内存 Secret 的幂等清理器。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RuntimeCleanup {

    public CleanupResult cleanup(
            Path executionRoot,
            RuntimeCredentialResolver.ResolvedCredential credential) {
        boolean credentialCleared = clearCredential(credential);
        boolean directoryDeleted = deleteRecursively(executionRoot);
        return new CleanupResult(directoryDeleted, credentialCleared);
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

    private boolean clearCredential(
            RuntimeCredentialResolver.ResolvedCredential credential) {
        if (credential == null) {
            return true;
        }
        try {
            credential.close();
            return credential.isCleared();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 不含路径或 Secret 的清理结果。
     */
    @Getter
    public static final class CleanupResult {

        private final boolean temporaryDirectoryDeleted;
        private final boolean credentialCleared;

        private CleanupResult(boolean temporaryDirectoryDeleted,
                              boolean credentialCleared) {
            this.temporaryDirectoryDeleted = temporaryDirectoryDeleted;
            this.credentialCleared = credentialCleared;
        }

        public boolean isSuccessful() {
            return temporaryDirectoryDeleted && credentialCleared;
        }
    }
}
