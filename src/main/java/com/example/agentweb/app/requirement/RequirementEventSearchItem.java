package com.example.agentweb.app.requirement;

/**
 * 跨需求事件检索行（admin 事件检索用，比 {@link RequirementEventView} 多 requirementId 定位列）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public record RequirementEventSearchItem(long id, String requirementId, String eventType, String actor,
                                         String fromStatus, String toStatus, String detail,
                                         long occurredAt) {
}
