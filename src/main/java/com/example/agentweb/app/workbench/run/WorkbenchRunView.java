package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import lombok.Getter;

/**
 * Owner 可见的 Workbench Run 详情投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunView {

    private final String runId;
    private final String workbenchId;
    private final String stageInstanceIdentifier;
    private final String sessionId;
    private final ChatRunStatus status;
    private final RunMode runMode;
    private final long lastEventSeq;

    private WorkbenchRunView(
            ChatRun run, WorkbenchStageRunSnapshot snapshot) {
        this.runId = run.getId().getValue();
        this.workbenchId = snapshot.getWorkbenchId().getValue();
        this.stageInstanceIdentifier =
                snapshot.getStageInstanceIdentifier();
        this.sessionId = run.getSessionId();
        this.status = run.getStatus();
        this.runMode = snapshot.getRunMode();
        this.lastEventSeq = run.getLastEventSeq();
    }

    public static WorkbenchRunView from(
            ChatRun run, WorkbenchStageRunSnapshot snapshot) {
        if (run == null || snapshot == null) {
            throw new IllegalArgumentException(
                    "workbench run view facts are required");
        }
        return new WorkbenchRunView(run, snapshot);
    }
}
