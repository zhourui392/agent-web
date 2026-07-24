package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 聚合准备阶段对话后返回给应用编排的不可变决策。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class StageConversationTurn {

    private final int attemptNumber;
    private final boolean attemptOpened;
    private final boolean duplicated;

    private StageConversationTurn(int attemptNumber, boolean attemptOpened, boolean duplicated) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("conversation attempt number must be positive");
        }
        this.attemptNumber = attemptNumber;
        this.attemptOpened = attemptOpened;
        this.duplicated = duplicated;
    }

    public static StageConversationTurn created(int attemptNumber, boolean attemptOpened) {
        return new StageConversationTurn(attemptNumber, attemptOpened, false);
    }

    public static StageConversationTurn duplicated(int attemptNumber) {
        return new StageConversationTurn(attemptNumber, false, true);
    }
}
