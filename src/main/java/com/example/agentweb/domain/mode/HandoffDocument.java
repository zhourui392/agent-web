package com.example.agentweb.domain.mode;

import com.example.agentweb.domain.shared.DomainText;

import java.time.Instant;

/**
 * 模式切换时导出的交接记录（不可变、无状态机）。
 *
 * <p>一条记录同时落 {@code (fromSessionId, idempotencyKey)} 唯一约束兜底重复提交，
 * 与 {@code chat_run.idempotency_key} 的幂等模式一致。</p>
 *
 * @author alex
 * @since 2026-08-20
 */
public class HandoffDocument {

    private final String id;
    private final String fromSessionId;
    private final String toSessionId;
    private final String fromModeId;
    private final String toModeId;
    /** 相对会话工作目录的路径，形如 {@code .workbench/handoff/<id>.md}。 */
    private final String filePath;
    private final String idempotencyKey;
    private final Instant createdAt;

    public HandoffDocument(String id, String fromSessionId, String toSessionId,
                           String fromModeId, String toModeId, String filePath,
                           String idempotencyKey, Instant createdAt) {
        this.id = DomainText.require(id, "handoff id", 128);
        this.fromSessionId = DomainText.require(fromSessionId, "handoff source session", 128);
        this.toSessionId = DomainText.require(toSessionId, "handoff target session", 128);
        this.fromModeId = optional(fromModeId);
        this.toModeId = optional(toModeId);
        this.filePath = DomainText.require(filePath, "handoff file path", 1024);
        this.idempotencyKey = DomainText.require(idempotencyKey, "handoff idempotency key", 128);
        this.createdAt = DomainText.requireTime(createdAt, "handoff created at");
    }

    private static String optional(String value) {
        return value == null || value.trim().isEmpty() ? null : DomainText.require(value, "handoff mode id", 128);
    }

    public String getId() {
        return id;
    }

    public String getFromSessionId() {
        return fromSessionId;
    }

    public String getToSessionId() {
        return toSessionId;
    }

    public String getFromModeId() {
        return fromModeId;
    }

    public String getToModeId() {
        return toModeId;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
