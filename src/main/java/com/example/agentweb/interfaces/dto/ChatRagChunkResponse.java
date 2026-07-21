package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import com.example.agentweb.domain.refinery.RagChunk;

import java.time.Instant;
import java.util.List;

/**
 * chat-rag 召回库存的列表项 (管理台展示用, 不含 embedding 向量).
 *
 * <p>{@code status} 在 {@link #from} 中按 (archivedAt, expiresAt, now) 实时算出, 与
 * {@code SqliteRagChunkRepo} 的 "可召回" 口径一致 ({@code expiresAt <= now} 即过期):
 * 即便 scheduler 尚未把过期行软删 (archived_at 仍空), 也会正确显示为 ARCHIVED。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-01
 */
@Getter
public class ChatRagChunkResponse {

    /** 可召回: 未归档且未过期. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 已归档或已过期, 不再参与召回. */
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    private final String id;
    private final String title;
    private final double score;
    private final String ttlCategory;
    private final String conclusion;
    private final List<String> triggerSignals;
    private final String sourceSessionId;
    private final String sourceMsgRange;
    private final String agentType;
    private final String createdAt;
    private final String expiresAt;
    private final String archivedAt;
    private final String status;

    private ChatRagChunkResponse(RagChunk c, String status) {
        this.id = c.getId();
        this.title = c.getContent().getTitle();
        this.score = c.getScore();
        this.ttlCategory = c.getTtlCategory().name();
        this.conclusion = c.getContent().getConclusion();
        this.triggerSignals = c.getContent().getTriggerSignals();
        this.sourceSessionId = c.getSourceSessionId();
        this.sourceMsgRange = c.getSourceMsgRange();
        this.agentType = c.getAgentType().name();
        this.createdAt = c.getCreatedAt() == null ? null : c.getCreatedAt().toString();
        this.expiresAt = c.getExpiresAt() == null ? null : c.getExpiresAt().toString();
        this.archivedAt = c.getArchivedAt() == null ? null : c.getArchivedAt().toString();
        this.status = status;
    }

    public static ChatRagChunkResponse from(RagChunk c, Instant now) {
        boolean expired = c.getExpiresAt() != null && !c.getExpiresAt().isAfter(now);
        boolean archived = c.getArchivedAt() != null || expired;
        return new ChatRagChunkResponse(c, archived ? STATUS_ARCHIVED : STATUS_ACTIVE);
    }
}
