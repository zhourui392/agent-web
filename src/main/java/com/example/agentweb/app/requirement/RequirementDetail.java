package com.example.agentweb.app.requirement;

import java.util.List;

/**
 * 需求详情投影（读侧 DTO），抽屉概览 + 计划 Tab 数据源。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public record RequirementDetail(
        String id,
        String title,
        String description,
        String status,
        String statusBeforeSuspend,
        String source,
        String sourceRef,
        String owner,
        List<String> participants,
        String workspaceId,
        String planText,
        Long planAttachedAt,
        long createdAt,
        long updatedAt) {
}
