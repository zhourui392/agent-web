package com.example.agentweb.domain.workbench;

import lombok.Getter;

/**
 * Workbench 领域不变量被拒绝时的统一异常。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchDomainException extends IllegalStateException {

    private static final String RUN_BINDING_CORRUPTED_MESSAGE =
            "workbench run binding is corrupted";

    private final WorkbenchErrorCode code;

    public WorkbenchDomainException(WorkbenchErrorCode code, String message) {
        super(message);
        if (code == null) {
            throw new IllegalArgumentException("workbench error code must not be null");
        }
        this.code = code;
    }

    public static WorkbenchDomainException runBindingCorrupted() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                RUN_BINDING_CORRUPTED_MESSAGE);
    }
}
