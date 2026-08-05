package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import lombok.Getter;

/**
 * Dynamic Stage Run 提交或精确幂等重放的公开应用结果。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageRunSubmissionResult {

    private final String runId;
    private final String sessionId;
    private final ChatRunStatus status;
    private final WorkbenchStageStatus stageStatus;
    private final long workbenchVersion;
    private final String capabilitySnapshotHash;
    private final String repositoryScopeHash;
    private final boolean replayed;

    private WorkbenchStageRunSubmissionResult(
            ChatRun run, WorkbenchStageRunSnapshot snapshot,
            Workbench workbench, boolean replayed) {
        this.runId = run.getId().getValue();
        this.sessionId = run.getSessionId();
        this.status = run.getStatus();
        this.stageStatus = workbench.stage(
                snapshot.getStageInstanceIdentifier()).getStatus();
        this.workbenchVersion = workbench.getVersion();
        this.capabilitySnapshotHash =
                snapshot.getCapabilityBinding().getBindingHash();
        this.repositoryScopeHash = snapshot.getRepositoryScopeHash();
        this.replayed = replayed;
    }

    public static WorkbenchStageRunSubmissionResult from(
            ChatRun run, WorkbenchStageRunSnapshot snapshot,
            Workbench workbench, boolean replayed) {
        if (run == null || snapshot == null || workbench == null) {
            throw new IllegalArgumentException(
                    "Workbench Stage Run submission result facts are required");
        }
        return new WorkbenchStageRunSubmissionResult(
                run, snapshot, workbench, replayed);
    }
}
