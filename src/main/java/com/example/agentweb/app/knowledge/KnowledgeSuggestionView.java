package com.example.agentweb.app.knowledge;

import java.util.List;

/**
 * 收件箱列表读模型（CQRS 读侧投影，不携带聚合行为）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public record KnowledgeSuggestionView(
        String id,
        String requirementId,
        String scope,
        String sourceRef,
        String title,
        List<String> triggerSignals,
        String phenomenon,
        String rootCause,
        String solution,
        String notes,
        String status,
        String rejectReason,
        String issueId,
        long createdAt) {
}
