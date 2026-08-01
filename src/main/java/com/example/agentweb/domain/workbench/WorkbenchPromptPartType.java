package com.example.agentweb.domain.workbench;

/**
 * Workbench 单轮 Prompt 的固定优先级部件。
 *
 * @author alex
 * @since 2026-08-01
 */
public enum WorkbenchPromptPartType {
    PLATFORM_SAFETY(true),
    ENVIRONMENT_GUARDRAIL(true),
    REPOSITORY_SCOPE(true),
    PHASE_RULES(true),
    SELECTED_CAPABILITIES(true),
    UPSTREAM_HANDOFF(false),
    WORKSPACE_CONTEXT(true),
    ORIGINAL_GOAL(true),
    ATTACHMENTS(false),
    PHASE_HISTORY(false),
    USER_INPUT(true),
    OUTPUT_INSTRUCTION(true);

    private final boolean required;

    WorkbenchPromptPartType(boolean required) {
        this.required = required;
    }

    public boolean isRequired() {
        return required;
    }
}
