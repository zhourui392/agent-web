package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

/**
 * 阶段对话事务准备结果。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class PreparedHarnessConversation {

    private final String runId;
    private final HarnessStage stage;
    private final int attemptNumber;
    private final boolean duplicated;

    public PreparedHarnessConversation(String runId, HarnessStage stage,
                                       int attemptNumber, boolean duplicated) {
        if (runId == null || runId.trim().isEmpty() || stage == null || attemptNumber < 1) {
            throw new IllegalArgumentException("prepared conversation identity is invalid");
        }
        this.runId = runId.trim();
        this.stage = stage;
        this.attemptNumber = attemptNumber;
        this.duplicated = duplicated;
    }
}
