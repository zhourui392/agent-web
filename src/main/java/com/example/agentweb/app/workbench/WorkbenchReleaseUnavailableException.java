package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.agentrun.AgentRuntimeUnavailableException;

/**
 * Workbench 发布门禁关闭时的稳定可用性错误。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkbenchReleaseUnavailableException
        extends AgentRuntimeUnavailableException {

    private WorkbenchReleaseUnavailableException(
            String code, String message) {
        super(code, message);
    }

    public static WorkbenchReleaseUnavailableException creation() {
        return new WorkbenchReleaseUnavailableException(
                "WORKBENCH_CREATE_UNAVAILABLE",
                "workbench creation is unavailable");
    }

    public static WorkbenchReleaseUnavailableException run() {
        return new WorkbenchReleaseUnavailableException(
                "WORKBENCH_RUN_UNAVAILABLE",
                "workbench run is unavailable");
    }

    public static WorkbenchReleaseUnavailableException highImpactExecution() {
        return new WorkbenchReleaseUnavailableException(
                "WORKBENCH_OPERATION_EXECUTOR_UNAVAILABLE",
                "workbench high-impact operation executor is unavailable");
    }
}
