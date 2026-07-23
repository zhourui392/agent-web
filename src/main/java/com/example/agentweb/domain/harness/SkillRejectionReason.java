package com.example.agentweb.domain.harness;

/**
 * 未自动启用 Skill 的可观测原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum SkillRejectionReason {
    WORKSPACE_NOT_APPROVED,
    STAGE_INCOMPATIBLE,
    RUNTIME_INCOMPATIBLE,
    TECH_TAG_NOT_MATCHED
}
