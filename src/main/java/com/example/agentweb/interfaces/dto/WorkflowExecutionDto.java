package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.WorkflowExecution;
import lombok.Getter;

/**
 * 工作流执行响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowExecutionDto {

    private final String id;
    private final String workflowId;
    private final String status;
    private final String inputsJson;
    private final String startedAt;
    private final String finishedAt;
    private final String errorMessage;
    private final String createdBy;

    public WorkflowExecutionDto(WorkflowExecution execution) {
        this.id = execution.getId();
        this.workflowId = execution.getWorkflowId();
        this.status = execution.getStatus().name();
        this.inputsJson = execution.getInputsJson();
        this.startedAt = execution.getStartedAt().toString();
        this.finishedAt = execution.getFinishedAt() == null ? null : execution.getFinishedAt().toString();
        this.errorMessage = execution.getErrorMessage();
        this.createdBy = execution.getCreatedBy();
    }

    /**
     * 领域对象转 DTO。
     *
     * @param execution 执行记录
     * @return DTO
     */
    public static WorkflowExecutionDto from(WorkflowExecution execution) {
        return new WorkflowExecutionDto(execution);
    }
}
