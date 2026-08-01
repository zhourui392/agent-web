package com.example.agentweb.app.workbench.handoff;

/**
 * Handoff 应用编排的资源生命周期错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum HandoffApplicationErrorCode {
    WORKBENCH_NOT_FOUND,
    HANDOFF_NOT_FOUND,
    VERSION_CONFLICT,
    SOURCE_CHANGED,
    RUN_REFERENCE_INVALID
}
