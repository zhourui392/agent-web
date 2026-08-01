package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;

/**
 * Workbench Run 私有、不可变的最终 Prompt 正文。
 *
 * <p>公开 Snapshot 只保存 Hash 和 Part 元数据；本对象保存 Runtime 真正接收的
 * 正文，并在构造时校验正文与 Hash 同源。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunPromptPayload {

    private final String runId;
    private final String finalPrompt;
    private final String promptHash;
    private final WorkbenchPromptHistoryDelivery historyDelivery;
    private final Instant createdAt;

    private WorkbenchRunPromptPayload(
            String runId, String finalPrompt, String promptHash,
            WorkbenchPromptHistoryDelivery historyDelivery,
            Instant createdAt) {
        this.runId = DomainText.require(
                runId, "workbench prompt run id", 128);
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "workbench final prompt must not be blank");
        }
        this.finalPrompt = finalPrompt;
        this.promptHash = DomainText.requireSha256(
                promptHash, "workbench final prompt hash");
        if (!CanonicalHashing.sha256(finalPrompt).equals(this.promptHash)) {
            throw new IllegalArgumentException(
                    "workbench final prompt hash does not match content");
        }
        if (historyDelivery == null) {
            throw new IllegalArgumentException(
                    "workbench prompt history delivery must not be null");
        }
        this.historyDelivery = historyDelivery;
        this.createdAt = DomainText.requireTime(
                createdAt, "workbench prompt created at");
    }

    public static WorkbenchRunPromptPayload freeze(
            String runId, String finalPrompt,
            WorkbenchPromptHistoryDelivery historyDelivery,
            Instant createdAt) {
        return new WorkbenchRunPromptPayload(
                runId, finalPrompt, CanonicalHashing.sha256(finalPrompt),
                historyDelivery, createdAt);
    }

    public static WorkbenchRunPromptPayload restore(
            String runId, String finalPrompt, String promptHash,
            WorkbenchPromptHistoryDelivery historyDelivery,
            Instant createdAt) {
        return new WorkbenchRunPromptPayload(
                runId, finalPrompt, promptHash, historyDelivery, createdAt);
    }
}
