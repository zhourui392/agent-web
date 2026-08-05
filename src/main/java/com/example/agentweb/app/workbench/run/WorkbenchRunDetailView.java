package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import lombok.Getter;

/**
 * 可在完成态恢复的 Workbench Run 安全详情。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunDetailView {

    private final String runId;
    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String sessionId;
    private final ChatRunStatus status;
    private final RunMode runMode;
    private final long lastEventSeq;
    private final long earliestRetainedSeq;
    private final long createdAt;
    private final Long startedAt;
    private final Long finishedAt;
    private final Integer exitCode;
    private final String failureCode;
    private final String capabilitySnapshotHash;
    private final String repositoryScopeHash;

    public WorkbenchRunDetailView(
            String runId, String workbenchId,
            String stageInstanceIdentifier,
            String sessionId, ChatRunStatus status, RunMode runMode,
            long lastEventSeq, long earliestRetainedSeq, long createdAt,
            Long startedAt, Long finishedAt, Integer exitCode,
            String failureCode, String capabilitySnapshotHash,
            String repositoryScopeHash) {
        this.runId = runId;
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = stageInstanceIdentifier;
        this.sessionId = sessionId;
        this.status = status;
        this.runMode = runMode;
        this.lastEventSeq = lastEventSeq;
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.exitCode = exitCode;
        this.failureCode = failureCode;
        this.capabilitySnapshotHash = capabilitySnapshotHash;
        this.repositoryScopeHash = repositoryScopeHash;
    }
}
