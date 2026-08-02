package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.workbench.WorkbenchAdminAction;
import lombok.Getter;

/**
 * Admin Workbench Run 运维动作的有界响应。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchRunActionResult {

    private final String workbenchId;
    private final String runId;
    private final WorkbenchAdminAction action;
    private final String outcome;
    private final String runStatus;
    private final long acceptedAt;

    public AdminWorkbenchRunActionResult(
            String workbenchId, String runId,
            WorkbenchAdminAction action, String outcome,
            String runStatus, long acceptedAt) {
        this.workbenchId = workbenchId;
        this.runId = runId;
        this.action = action;
        this.outcome = outcome;
        this.runStatus = runStatus;
        this.acceptedAt = acceptedAt;
    }
}
