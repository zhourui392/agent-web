package com.example.agentweb.app.refinery;

import java.util.List;

/**
 * 管理台召回库存读模型，不包含 embedding。
 *
 * @author alex
 * @since 2026-07-23
 */
public record RefineryChunkView(
        String id,
        String title,
        double score,
        String ttlCategory,
        String conclusion,
        List<String> triggerSignals,
        String sourceSessionId,
        String sourceMsgRange,
        String agentType,
        String createdAt,
        String expiresAt,
        String archivedAt,
        String status) {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";
}
