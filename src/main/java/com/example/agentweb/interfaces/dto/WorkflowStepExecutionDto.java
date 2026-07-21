package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import lombok.Getter;

/**
 * 工作流步骤执行响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowStepExecutionDto {

    private final String id;
    private final String executionId;
    private final int stepIndex;
    private final String stepName;
    private final String status;
    private final String prompt;
    private final String output;
    private final String errorMessage;
    private final String startedAt;
    private final String finishedAt;

    public WorkflowStepExecutionDto(WorkflowStepExecution step) {
        this.id = step.getId();
        this.executionId = step.getExecutionId();
        this.stepIndex = step.getStepIndex();
        this.stepName = step.getStepName();
        this.status = step.getStatus().name();
        this.prompt = step.getPrompt();
        this.output = step.getOutput();
        this.errorMessage = step.getErrorMessage();
        this.startedAt = step.getStartedAt().toString();
        this.finishedAt = step.getFinishedAt() == null ? null : step.getFinishedAt().toString();
    }

    /**
     * 领域对象转 DTO。
     *
     * @param step 步骤执行记录
     * @return DTO
     */
    public static WorkflowStepExecutionDto from(WorkflowStepExecution step) {
        return new WorkflowStepExecutionDto(step);
    }
}
