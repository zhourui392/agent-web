package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

/**
 * 一个 Workbench Run 的安全历史摘要。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunListItemView {

    private final String runId;
    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String sessionId;
    private final ChatRunStatus status;
    private final RunMode runMode;
    private final long lastEventSeq;
    private final long createdAt;
    private final Long startedAt;
    private final Long finishedAt;
    private final String failureCode;

    public WorkbenchRunListItemView(
            String runId, String workbenchId,
            String stageInstanceIdentifier,
            String sessionId, ChatRunStatus status, RunMode runMode,
            long lastEventSeq, long createdAt, Long startedAt,
            Long finishedAt, String failureCode) {
        this.runId = runId;
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = stageInstanceIdentifier;
        this.sessionId = sessionId;
        this.status = status;
        this.runMode = runMode;
        this.lastEventSeq = lastEventSeq;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.failureCode = failureCode;
    }
}
