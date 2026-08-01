package com.example.agentweb.app.harness;

/**
 * Harness 无法把请求目录识别为可采集基线的 Git 工作区。
 *
 * @author alex
 * @since 2026-07-31
 */
public class InvalidHarnessWorkspaceException extends RuntimeException {

    public InvalidHarnessWorkspaceException() {
        super("Harness working directory must be inside a Git repository");
    }
}
