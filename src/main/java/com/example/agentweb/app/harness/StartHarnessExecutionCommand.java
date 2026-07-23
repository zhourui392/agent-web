package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

/**
 * 为已有 Capability Snapshot 启动一次 RuntimeExecution 的命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class StartHarnessExecutionCommand {

    private final String runId;
    private final HarnessStage stage;
    private final String idempotencyKey;

    public StartHarnessExecutionCommand(String runId, HarnessStage stage, String idempotencyKey) {
        if (runId == null || runId.trim().isEmpty() || stage == null
                || idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.trim().length() > 128) {
            throw new IllegalArgumentException("execution run, stage and idempotency key are required");
        }
        this.runId = runId.trim();
        this.stage = stage;
        this.idempotencyKey = idempotencyKey.trim();
    }
}
