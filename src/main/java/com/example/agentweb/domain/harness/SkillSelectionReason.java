package com.example.agentweb.domain.harness;

/**
 * Skill 被选中的确定性原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum SkillSelectionReason {
    STAGE_DEFAULT,
    USER_EXPLICIT,
    TECH_TAG,
    REQUIRED_DEPENDENCY
}
