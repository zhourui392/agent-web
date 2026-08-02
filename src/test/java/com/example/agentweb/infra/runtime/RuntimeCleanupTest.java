package com.example.agentweb.infra.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 临时目录的幂等清理契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCleanupTest {

    @TempDir
    Path tempDir;

    @Test
    void recursivelyDeletesExecutionRootIdempotently() throws Exception {
        Path executionRoot = Files.createDirectories(tempDir.resolve("execution/home/nested"))
                .getParent().getParent();
        Files.write(executionRoot.resolve("home/nested/config"),
                "temporary".getBytes(StandardCharsets.UTF_8));
        RuntimeCleanup cleanup = new RuntimeCleanup();

        RuntimeCleanup.CleanupResult first = cleanup.cleanup(executionRoot);
        RuntimeCleanup.CleanupResult second = cleanup.cleanup(executionRoot);

        assertTrue(first.isSuccessful());
        assertTrue(first.isTemporaryDirectoryDeleted());
        assertTrue(second.isSuccessful());
        assertFalse(Files.exists(executionRoot));
    }
}
