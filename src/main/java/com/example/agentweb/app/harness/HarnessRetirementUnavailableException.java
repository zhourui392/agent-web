package com.example.agentweb.app.harness;

import com.example.agentweb.domain.agentrun.AgentRuntimeUnavailableException;

/**
 * Harness 退役窗口关闭对应能力时的稳定可用性错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class HarnessRetirementUnavailableException
        extends AgentRuntimeUnavailableException {

    private HarnessRetirementUnavailableException(
            String code, String message) {
        super(code, message);
    }

    public static HarnessRetirementUnavailableException creation() {
        return new HarnessRetirementUnavailableException(
                "HARNESS_CREATION_DISABLED",
                "harness run creation is disabled");
    }

    public static HarnessRetirementUnavailableException mutation() {
        return new HarnessRetirementUnavailableException(
                "HARNESS_MUTATION_DISABLED",
                "harness mutation is disabled");
    }

    public static HarnessRetirementUnavailableException export() {
        return new HarnessRetirementUnavailableException(
                "HARNESS_EXPORT_DISABLED",
                "harness export is disabled");
    }
}
