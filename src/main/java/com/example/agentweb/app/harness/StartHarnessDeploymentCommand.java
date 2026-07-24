package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * 启动一次已批准 local 部署的应用命令。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class StartHarnessDeploymentCommand {

    private final String runId;
    private final String templateId;
    private final String approvedInputBaselineHash;
    private final String idempotencyKey;

    public StartHarnessDeploymentCommand(String runId, String templateId,
                                         String approvedInputBaselineHash,
                                         String idempotencyKey) {
        this.runId = require(runId, "run id");
        this.templateId = require(templateId, "deployment template id");
        String baselineHash = require(approvedInputBaselineHash,
                "deployment approved input baseline hash").toLowerCase();
        if (!baselineHash.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "deployment approved input baseline hash must be SHA-256");
        }
        this.approvedInputBaselineHash = baselineHash;
        this.idempotencyKey = require(idempotencyKey, "deployment idempotency key");
    }

    private String require(String value, String name) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 128) {
            throw new IllegalArgumentException(name + " must not be blank or oversized");
        }
        return value.trim();
    }
}
