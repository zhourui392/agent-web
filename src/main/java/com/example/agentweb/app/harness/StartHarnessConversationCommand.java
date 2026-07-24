package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

/**
 * 用户通过对话修改当前 Harness 阶段的应用命令。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class StartHarnessConversationCommand {

    private static final int MAXIMUM_ID_LENGTH = 128;
    private static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final int MAXIMUM_MESSAGE_LENGTH = 20000;

    private final String runId;
    private final HarnessStage stage;
    private final String idempotencyKey;
    private final String message;

    public StartHarnessConversationCommand(String runId, HarnessStage stage,
                                           String idempotencyKey, String message) {
        if (runId == null || runId.trim().isEmpty()
                || runId.trim().length() > MAXIMUM_ID_LENGTH
                || stage == null
                || idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.trim().length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH
                || message == null || message.trim().isEmpty()
                || message.trim().length() > MAXIMUM_MESSAGE_LENGTH) {
            throw new IllegalArgumentException(
                    "conversation run, stage, idempotency key and message are required");
        }
        this.runId = runId.trim();
        this.stage = stage;
        this.idempotencyKey = idempotencyKey.trim();
        this.message = message.trim();
    }
}
