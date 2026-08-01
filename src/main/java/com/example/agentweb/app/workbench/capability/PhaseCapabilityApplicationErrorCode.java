package com.example.agentweb.app.workbench.capability;

/**
 * Phase Capability 应用层资源生命周期错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum PhaseCapabilityApplicationErrorCode {
    OVERRIDE_ALREADY_EXISTS,
    OVERRIDE_NOT_FOUND,
    VERSION_CONFLICT,
    ESCALATION_DENIED
}
