package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import com.example.agentweb.domain.refinery.DiscardedRefineRecord;

/**
 * below-threshold 丢弃记录的列表项. 字段形状刻意对齐 {@link ChatRagChunkResponse}
 * (title/conclusion/score/ttlCategory/sourceSessionId/agentType/createdAt), 让前端"召回历史"
 * 弹窗复用同一张卡片渲染; {@code status} 固定 {@value #STATUS_DISCARDED}, 另带 reason/threshold.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-04
 */
@Getter
public class DiscardedRecordResponse {

    /** 固定状态值: 低分被丢弃. */
    public static final String STATUS_DISCARDED = "DISCARDED";

    private final String id;
    private final String title;
    private final double score;
    private final double threshold;
    private final String ttlCategory;
    private final String conclusion;
    private final String sourceType;
    private final String sourceSessionId;
    private final String agentType;
    private final String env;
    private final String createdAt;
    private final String reason;
    private final String status;

    private DiscardedRecordResponse(DiscardedRefineRecord r) {
        this.id = r.getId();
        this.title = r.getTitle();
        this.score = r.getScore();
        this.threshold = r.getThreshold();
        this.ttlCategory = r.getTtlCategory() == null ? null : r.getTtlCategory().name();
        this.conclusion = r.getConclusion();
        this.sourceType = r.getSourceType().name();
        this.sourceSessionId = r.getSourceSessionId();
        this.agentType = r.getAgentType();
        this.env = r.getEnv();
        this.createdAt = r.getCreatedAt() == null ? null : r.getCreatedAt().toString();
        this.reason = r.getReason();
        this.status = STATUS_DISCARDED;
    }

    public static DiscardedRecordResponse from(DiscardedRefineRecord r) {
        return new DiscardedRecordResponse(r);
    }
}
