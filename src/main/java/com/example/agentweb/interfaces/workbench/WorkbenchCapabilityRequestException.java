package com.example.agentweb.interfaces.workbench;

/**
 * Capability HTTP 边界请求格式错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchCapabilityRequestException
        extends IllegalArgumentException {

    public WorkbenchCapabilityRequestException() {
        super("workbench capability request is invalid");
    }
}
