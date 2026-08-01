package com.example.agentweb.app.workbench;

import lombok.Getter;

/**
 * 不泄露 Git stderr、未授权绝对路径或文件内容的 Workspace 失败。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceOperationException extends RuntimeException {

    private final WorkspaceFailureCode code;

    public WorkspaceOperationException(WorkspaceFailureCode code, String safeMessage) {
        super(safeMessage);
        this.code = requireCode(code);
    }

    public WorkspaceOperationException(WorkspaceFailureCode code, String safeMessage,
                                       Throwable cause) {
        super(safeMessage, cause);
        this.code = requireCode(code);
    }

    private static WorkspaceFailureCode requireCode(WorkspaceFailureCode code) {
        if (code == null) {
            throw new IllegalArgumentException("workspace failure code must not be null");
        }
        return code;
    }
}
