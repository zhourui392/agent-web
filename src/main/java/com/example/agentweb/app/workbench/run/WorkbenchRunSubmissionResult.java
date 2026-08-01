package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import lombok.Getter;

/**
 * Workbench Run 提交或精确幂等重放的公开应用结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunSubmissionResult {

    private final String runId;
    private final String sessionId;
    private final ChatRunStatus status;
    private final WorkbenchPhaseStatus phaseStatus;
    private final long workbenchVersion;
    private final String capabilitySnapshotHash;
    private final String repositoryScopeHash;
    private final boolean replayed;

    private WorkbenchRunSubmissionResult(
            ChatRun run, WorkbenchRunSnapshot snapshot,
            Workbench workbench, boolean replayed) {
        this.runId = run.getId().getValue();
        this.sessionId = run.getSessionId();
        this.status = run.getStatus();
        this.phaseStatus = workbench.phaseStatus(snapshot.getPhase());
        this.workbenchVersion = workbench.getVersion();
        this.capabilitySnapshotHash =
                snapshot.getCapabilityBinding().getBindingHash();
        this.repositoryScopeHash = snapshot.getRepositoryScopeHash();
        this.replayed = replayed;
    }

    public static WorkbenchRunSubmissionResult from(
            ChatRun run, WorkbenchRunSnapshot snapshot,
            Workbench workbench, boolean replayed) {
        if (run == null || snapshot == null || workbench == null) {
            throw new IllegalArgumentException(
                    "workbench run submission result facts are required");
        }
        return new WorkbenchRunSubmissionResult(
                run, snapshot, workbench, replayed);
    }
}
