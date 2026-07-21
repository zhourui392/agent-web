package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.WorkflowStep;
import lombok.Getter;

/**
 * 工作流步骤响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowStepDto {

    private final String name;
    private final String promptTemplate;
    private final long timeoutSeconds;

    public WorkflowStepDto(String name, String promptTemplate, long timeoutSeconds) {
        this.name = name;
        this.promptTemplate = promptTemplate;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * 领域对象转 DTO。
     *
     * @param step 步骤领域对象
     * @return DTO
     */
    public static WorkflowStepDto from(WorkflowStep step) {
        return new WorkflowStepDto(step.getName(), step.getPromptTemplate(), step.getTimeoutSeconds());
    }
}
