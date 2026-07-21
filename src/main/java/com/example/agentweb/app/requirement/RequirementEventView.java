package com.example.agentweb.app.requirement;

/**
 * 事件时间线投影（读侧 DTO）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public record RequirementEventView(
        long id,
        String eventType,
        String actor,
        String fromStatus,
        String toStatus,
        String detail,
        long occurredAt) {
}
