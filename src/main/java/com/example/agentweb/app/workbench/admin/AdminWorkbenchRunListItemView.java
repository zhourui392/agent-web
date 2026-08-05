package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

/**
 * Admin Workbench Run 安全摘要。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchRunListItemView {

    private final String runId;
    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final ChatRunStatus status;
    private final RunMode runMode;
    private final long lastEventSeq;
    private final long createdAt;
    private final Long startedAt;
    private final Long cancelRequestedAt;
    private final Long finishedAt;
    private final String failureCode;

    public AdminWorkbenchRunListItemView(
            String runId, String workbenchId,
            String stageInstanceIdentifier,
            ChatRunStatus status, RunMode runMode, long lastEventSeq,
            long createdAt, Long startedAt, Long cancelRequestedAt,
            Long finishedAt, String failureCode) {
        this.runId = runId;
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = stageInstanceIdentifier;
        this.status = status;
        this.runMode = runMode;
        this.lastEventSeq = lastEventSeq;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.cancelRequestedAt = cancelRequestedAt;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
    }
}
