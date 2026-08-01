package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import lombok.Getter;

/**
 * Workbench Run 停止请求结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunStopResult {

    private final String runId;
    private final ChatRunStatus status;

    private WorkbenchRunStopResult(ChatRun run) {
        this.runId = run.getId().getValue();
        this.status = run.getStatus();
    }

    public static WorkbenchRunStopResult from(ChatRun run) {
        if (run == null) {
            throw new IllegalArgumentException(
                    "workbench run stop result requires a run");
        }
        return new WorkbenchRunStopResult(run);
    }
}
