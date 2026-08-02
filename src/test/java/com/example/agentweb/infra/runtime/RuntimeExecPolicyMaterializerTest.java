package com.example.agentweb.infra.runtime;

import com.example.agentweb.domain.runtime.RuntimeCommandPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Codex Exec Policy 的前置高影响操作门禁物化测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class RuntimeExecPolicyMaterializerTest {

    @TempDir
    Path tempDir;

    @Test
    void materializesOpaqueShellFallbackAndAbsoluteExecutablePrefixes()
            throws Exception {
        Path executableDirectory = Files.createDirectory(tempDir.resolve("bin"));
        Path git = Files.write(executableDirectory.resolve("git"), new byte[0]);
        Path bash = Files.write(executableDirectory.resolve("bash"), new byte[0]);
        Path isolatedHome = Files.createDirectory(tempDir.resolve("home"));
        RuntimeExecPolicyMaterializer materializer =
                new RuntimeExecPolicyMaterializer(
                        RuntimeCommandPolicy.platformDefault(),
                        Collections.singletonList(executableDirectory));

        Path policy = materializer.materialize(isolatedHome);

        String rules = new String(Files.readAllBytes(policy), StandardCharsets.UTF_8);
        assertTrue(rules.contains(
                "prefix_rule(pattern=[\"bash\",\"-lc\"],decision=\"forbidden\""));
        assertTrue(rules.contains(
                "prefix_rule(pattern=[\"powershell\"],decision=\"forbidden\""));
        assertTrue(rules.contains(
                "prefix_rule(pattern=[\""
                        + escaped(git.toAbsolutePath().normalize())
                        + "\",\"push\"],decision=\"forbidden\""));
        assertTrue(rules.contains(
                "prefix_rule(pattern=[\""
                        + escaped(bash.toAbsolutePath().normalize())
                        + "\",\"-lc\"],decision=\"forbidden\""));
        assertFalse(rules.contains(tempDir.resolve("not-in-path").toString()));
    }

    private String escaped(Path path) {
        return path.toString().replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
