package com.example.agentweb.domain.requirement;

/**
 * 需求生命周期状态（master-plan §3.1 状态机）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum RequirementStatus {

    INTAKE,
    PLANNED,
    APPROVED,
    IMPLEMENTING,
    VERIFYING,
    REVIEW,
    DELIVERED,
    SUSPENDED,
    ARCHIVED;

    /**
     * 是否终态（T15：终态拒绝一切迁移）。读侧"活跃需求"计数也以此为准。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return this == DELIVERED || this == ARCHIVED;
    }
}
