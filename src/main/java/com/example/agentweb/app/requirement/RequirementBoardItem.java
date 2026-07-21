package com.example.agentweb.app.requirement;

/**
 * 看板卡片投影（读侧 DTO）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public record RequirementBoardItem(
        String id,
        String title,
        String status,
        String owner,
        long updatedAt,
        boolean planAttached,
        boolean workspaceAttached) {
}
