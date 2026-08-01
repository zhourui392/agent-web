package com.example.agentweb.infra.workspace;

/**
 * 有界 Git 子进程结果。
 *
 * @author alex
 * @since 2026-08-01
 */
final class WorkspaceGitCommandResult {

    private final int exitCode;
    private final byte[] output;

    WorkspaceGitCommandResult(int exitCode, byte[] output) {
        this.exitCode = exitCode;
        this.output = output.clone();
    }

    int getExitCode() {
        return exitCode;
    }

    byte[] getOutput() {
        return output.clone();
    }
}
