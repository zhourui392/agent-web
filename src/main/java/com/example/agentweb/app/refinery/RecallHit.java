package com.example.agentweb.app.refinery;

/**
 * 一条被召回的历史参考的展示视图, 用于通过 SSE 推给前端渲染"召回卡片".
 *
 * <p>只暴露展示所需字段, 不带 embedding 等内部细节。{@code conclusion} 在入库时已脱敏。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-31
 */
public final class RecallHit {

    private final String title;
    private final String conclusion;
    private final String sessionId;

    public RecallHit(String title, String conclusion, String sessionId) {
        this.title = title;
        this.conclusion = conclusion;
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getConclusion() {
        return conclusion;
    }

    public String getSessionId() {
        return sessionId;
    }
}
