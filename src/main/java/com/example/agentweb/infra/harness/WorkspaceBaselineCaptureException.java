package com.example.agentweb.infra.harness;

/**
 * Git 工作区基线无法在安全边界内确定时的失败关闭异常。
 *
 * @author alex
 * @since 2026-07-23
 */
public class WorkspaceBaselineCaptureException extends RuntimeException {

    public WorkspaceBaselineCaptureException(String message) {
        super(message);
    }

    public WorkspaceBaselineCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
