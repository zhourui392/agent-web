package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import com.example.agentweb.app.refinery.DiscardedRefineView;

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
    public static final String STATUS_DISCARDED = DiscardedRefineView.STATUS_DISCARDED;

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

    private DiscardedRecordResponse(DiscardedRefineView view) {
        this.id = view.id();
        this.title = view.title();
        this.score = view.score();
        this.threshold = view.threshold();
        this.ttlCategory = view.ttlCategory();
        this.conclusion = view.conclusion();
        this.sourceType = view.sourceType();
        this.sourceSessionId = view.sourceSessionId();
        this.agentType = view.agentType();
        this.env = view.env();
        this.createdAt = view.createdAt();
        this.reason = view.reason();
        this.status = view.status();
    }

    public static DiscardedRecordResponse from(DiscardedRefineView view) {
        return new DiscardedRecordResponse(view);
    }
}
