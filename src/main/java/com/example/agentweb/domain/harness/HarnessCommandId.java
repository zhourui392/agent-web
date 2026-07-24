package com.example.agentweb.domain.harness;

import java.nio.charset.StandardCharsets;

/**
 * 将 API 幂等键收敛为跨 Run 不冲突的稳定领域标识。
 *
 * @author alex
 * @since 2026-07-23
 */
public final class HarnessCommandId {

    private HarnessCommandId() {
    }

    public static String approval(String runId, HarnessStage stage, String decision,
                                  String idempotencyKey) {
        String canonical = DomainText.require(runId, "approval run id", 128) + '\n'
                + requireStage(stage).name() + '\n'
                + DomainText.require(decision, "approval decision", 32) + '\n'
                + DomainText.require(idempotencyKey, "approval idempotency key", 128);
        return ArtifactContent.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static HarnessStage requireStage(HarnessStage stage) {
        if (stage == null) {
            throw new IllegalArgumentException("approval stage must not be null");
        }
        return stage;
    }
}
