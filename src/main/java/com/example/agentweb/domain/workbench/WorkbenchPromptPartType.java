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
    STAGE_DEFINITION(false),
    STAGE_RULES(false),
    SELECTED_CAPABILITIES(true),
    GLOBAL_CONTEXT(false),
    WORKSPACE_CONTEXT(true),
    ORIGINAL_GOAL(true),
    ATTACHMENTS(false),
    STAGE_HISTORY(false),
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
