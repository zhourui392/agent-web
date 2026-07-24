package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

/**
 * 对话消息受理并启动 Runtime 后的轻量响应。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class HarnessConversationTurnResult {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final String executionId;
    private final String executionStatus;
    private final boolean duplicated;

    public HarnessConversationTurnResult(HarnessExecutionResult execution,
                                         boolean preparationDuplicated) {
        if (execution == null) {
            throw new IllegalArgumentException("conversation execution result must not be null");
        }
        this.runId = execution.getRunId();
        this.stage = execution.getStage();
        this.attemptNumber = execution.getAttemptNumber();
        this.executionId = execution.getExecutionId();
        this.executionStatus = execution.getStatus();
        this.duplicated = preparationDuplicated || execution.isDuplicated();
    }
}
