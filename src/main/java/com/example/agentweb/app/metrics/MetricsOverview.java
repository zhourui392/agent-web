package com.example.agentweb.app.metrics;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 管理后台总览指标读模型(纯投影 DTO,非聚合)。仅会话维度。
 *
 * <p>比率字段允许为 {@code null} 表示样本不足(如无人评分时准确率无意义),前端据此显示"—"。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
@Setter
public class MetricsOverview {

    private Chat chat = new Chat();

    /** 会话维度:规模、Agent 分布、用户评分准确率。 */
    @Getter
    @Setter
    public static class Chat {
        private long total;
        private Map<String, Long> byAgentType;
        /** feedback_rating 分布:CORRECT / PARTIALLY_CORRECT / INCORRECT。 */
        private Map<String, Long> feedback;
        /** CORRECT 占已评分会话比例;无评分样本时为 null。 */
        private Double accuracyRate;
    }
}
