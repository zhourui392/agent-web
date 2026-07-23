package com.example.agentweb.domain.harness;

/**
 * Harness Prompt 固定优先级部件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum PromptPartType {
    PLATFORM_SAFETY,
    ENVIRONMENT_GUARDRAIL,
    STAGE_CONTRACT,
    STAGE_SYSTEM,
    STAGE_TASK,
    STAGE_GATE_HINTS,
    SELECTED_SKILLS,
    UPSTREAM_ARTIFACTS,
    CURRENT_INPUT,
    OUTPUT_CONTRACT
}
