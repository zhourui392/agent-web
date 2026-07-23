package com.example.agentweb.app.refinery;

/**
 * 管理台低分丢弃记录读模型。
 *
 * @author alex
 * @since 2026-07-23
 */
public record DiscardedRefineView(
        String id,
        String title,
        double score,
        double threshold,
        String ttlCategory,
        String conclusion,
        String sourceType,
        String sourceSessionId,
        String agentType,
        String env,
        String createdAt,
        String reason,
        String status) {

    public static final String STATUS_DISCARDED = "DISCARDED";
}
