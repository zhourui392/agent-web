package com.example.agentweb.infra.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ProcessGitWorktreeGateway} 进程原语的轻量集成测试(真实子进程, 不起 Spring)。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class ProcessGitWorktreeGatewayTest {

    @TempDir
    Path tempDir;

    private final ProcessGitWorktreeGateway gateway = new ProcessGitWorktreeGateway();

    @Test
    @DisplayName("execWithTimeout: 子进程超过超时时间时返回 -1")
    void execWithTimeout_returnsMinusOne_When_ProcessExceedsTimeout() throws Exception {
        int exitCode = gateway.execWithTimeout(tempDir.toFile(), 1, sleepCommand());

        assertEquals(-1, exitCode);
    }

    private String[] sleepCommand() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return new String[]{"powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 2"};
        }
        return new String[]{"sh", "-c", "sleep 2"};
    }
}
