package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.CredentialReference;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 临时目录与内存 Credential 的幂等清理契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeCleanupTest {

    private static final String SECRET = "cleanup-secret";

    @TempDir
    Path tempDir;

    @Test
    void recursivelyDeletesExecutionRootAndClearsCredentialIdempotently() throws Exception {
        Path executionRoot = Files.createDirectories(tempDir.resolve("execution/home/nested"))
                .getParent().getParent();
        Files.write(executionRoot.resolve("home/nested/config"),
                "temporary".getBytes(StandardCharsets.UTF_8));
        RuntimeCredentialResolver resolver = new RuntimeCredentialResolver(
                () -> SECRET, name -> null);
        RuntimeCredentialResolver.ResolvedCredential credential =
                resolver.resolve(AgentType.CODEX,
                        CredentialReference.systemConfiguration());
        RuntimeCleanup cleanup = new RuntimeCleanup();

        RuntimeCleanup.CleanupResult first = cleanup.cleanup(executionRoot, credential);
        RuntimeCleanup.CleanupResult second = cleanup.cleanup(executionRoot, credential);

        assertTrue(first.isSuccessful());
        assertTrue(first.isTemporaryDirectoryDeleted());
        assertTrue(first.isCredentialCleared());
        assertTrue(second.isSuccessful());
        assertFalse(Files.exists(executionRoot));
        assertTrue(credential.isCleared());
    }
}
