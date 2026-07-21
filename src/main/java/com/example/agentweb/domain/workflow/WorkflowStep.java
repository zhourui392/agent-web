package com.example.agentweb.domain.workflow;

import lombok.Getter;

/**
 * 工作流中的一个串行 Agent 步骤。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowStep {

    private final String name;
    private final String promptTemplate;
    private final long timeoutSeconds;

    /**
     * 创建工作流步骤。
     *
     * @param name 步骤名,同一工作流内唯一
     * @param promptTemplate prompt 模板
     * @param timeoutSeconds 步骤超时秒数,小于等于 0 表示沿用 CLI 默认超时
     */
    public WorkflowStep(String name, String promptTemplate, long timeoutSeconds) {
        if (isBlank(name)) {
            throw new IllegalArgumentException("步骤名称不能为空");
        }
        if (isBlank(promptTemplate)) {
            throw new IllegalArgumentException("步骤 prompt 不能为空");
        }
        this.name = name.trim();
        this.promptTemplate = promptTemplate;
        this.timeoutSeconds = timeoutSeconds;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
