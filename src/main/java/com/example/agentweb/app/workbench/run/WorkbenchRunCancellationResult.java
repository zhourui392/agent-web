package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.chatrun.ChatRunCancellationDecision;
import lombok.Getter;

/**
 * Workbench Run 取消领域决议与公开停止结果。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunCancellationResult {

    private final ChatRunCancellationDecision decision;
    private final WorkbenchRunStopResult stopResult;

    public WorkbenchRunCancellationResult(
            ChatRunCancellationDecision decision,
            WorkbenchRunStopResult stopResult) {
        if (decision == null || stopResult == null) {
            throw new IllegalArgumentException(
                    "workbench run cancellation result is required");
        }
        this.decision = decision;
        this.stopResult = stopResult;
    }
}
