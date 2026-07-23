package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import com.example.agentweb.app.refinery.RefineryChunkView;

import java.util.List;

/**
 * chat-rag 召回库存的列表项 (管理台展示用, 不含 embedding 向量).
 *
 * <p>状态由 CQRS QueryService 按读模型口径投影，Interface 层只做边界转换。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-01
 */
@Getter
public class ChatRagChunkResponse {

    /** 可召回: 未归档且未过期. */
    public static final String STATUS_ACTIVE = RefineryChunkView.STATUS_ACTIVE;
    /** 已归档或已过期, 不再参与召回. */
    public static final String STATUS_ARCHIVED = RefineryChunkView.STATUS_ARCHIVED;

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

    private ChatRagChunkResponse(RefineryChunkView view) {
        this.id = view.id();
        this.title = view.title();
        this.score = view.score();
        this.ttlCategory = view.ttlCategory();
        this.conclusion = view.conclusion();
        this.triggerSignals = view.triggerSignals();
        this.sourceSessionId = view.sourceSessionId();
        this.sourceMsgRange = view.sourceMsgRange();
        this.agentType = view.agentType();
        this.createdAt = view.createdAt();
        this.expiresAt = view.expiresAt();
        this.archivedAt = view.archivedAt();
        this.status = view.status();
    }

    public static ChatRagChunkResponse from(RefineryChunkView view) {
        return new ChatRagChunkResponse(view);
    }
}
