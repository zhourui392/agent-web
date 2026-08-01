package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimePreflightErrorCode;
import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 版本探测进程的 token、超时和输出上限测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class ProcessRuntimeVersionProbeRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExecuteExecutableAndVersionAsTwoTokensWithoutShellExpansion()
            throws Exception {
        Path executable = script("codex stub.sh", "#!/bin/sh\n"
                + "test \"$#\" -eq 1 || exit 81\n"
                + "test \"$1\" = \"--version\" || exit 82\n"
                + "printf '%s\\n' 'codex-cli 0.145.0'\n");
        ProcessRuntimeVersionProbeRunner runner =
                new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(2L), 4096L);

        RuntimeVersionProbeResult result = runner.run(
                Arrays.asList(executable.toString(), "--version"));

        assertEquals(0, result.getExitCode());
        assertEquals("codex-cli 0.145.0\n", result.getOutput());
    }

    @Test
    void shouldTerminateProbeWhenTimeoutExpiresWithoutLeakingExecutablePath()
            throws Exception {
        Path executable = script("timeout.sh", "#!/bin/sh\n"
                + "while true; do :; done\n");
        ProcessRuntimeVersionProbeRunner runner =
                new ProcessRuntimeVersionProbeRunner(
                        Duration.ofMillis(50L), 4096L);

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> runner.run(
                        Arrays.asList(executable.toString(), "--version")));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_PROBE_TIMEOUT,
                failure.getErrorCode());
        assertFalse(failure.getMessage().contains(executable.toString()));
    }

    @Test
    void shouldTerminateProbeWhenMergedOutputExceedsLimitWithoutLeakingOutput()
            throws Exception {
        String sensitiveOutput = "secret-/absolute/repository-"
                + "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
        Path executable = script("oversized.sh", "#!/bin/sh\n"
                + "printf '%s' '" + sensitiveOutput + "'\n");
        ProcessRuntimeVersionProbeRunner runner =
                new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(2L), 16L);

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> runner.run(
                        Arrays.asList(executable.toString(), "--version")));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_PROBE_OUTPUT_LIMIT_EXCEEDED,
                failure.getErrorCode());
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getMessage().contains("/absolute/repository"));
    }

    @Test
    void shouldRejectUnsafeOrIncompleteCommandBeforeStartingProcess() {
        ProcessRuntimeVersionProbeRunner runner =
                new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(2L), 4096L);

        assertThrows(IllegalArgumentException.class,
                () -> runner.run(Arrays.asList("codex\nunsafe", "--version")));
        assertThrows(IllegalArgumentException.class,
                () -> runner.run(Arrays.asList("codex")));
    }

    @Test
    void shouldReturnStableStartFailureWithoutLeakingMissingExecutablePath() {
        Path missing = tempDir.resolve("private/missing-codex");
        ProcessRuntimeVersionProbeRunner runner =
                new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(2L), 4096L);

        RuntimePreflightException failure = assertThrows(
                RuntimePreflightException.class, () -> runner.run(
                        Arrays.asList(missing.toString(), "--version")));

        assertEquals(RuntimePreflightErrorCode.RUNTIME_PROBE_START_FAILED,
                failure.getErrorCode());
        assertFalse(failure.getMessage().contains(missing.toString()));
        assertNull(failure.getCause());
    }

    @Test
    void constructorShouldRejectUnboundedProbeSettings() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessRuntimeVersionProbeRunner(
                        Duration.ZERO, 4096L));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(1L), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ProcessRuntimeVersionProbeRunner(
                        Duration.ofSeconds(1L), 1024L * 1024L + 1L));
    }

    private Path script(String name, String content) throws Exception {
        Path script = tempDir.resolve(name);
        Files.write(script, content.getBytes(StandardCharsets.UTF_8));
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }
}
