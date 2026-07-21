package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.WorkflowExecution;
import lombok.Getter;

/**
 * 工作流运行响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowRunResponse {

    private final String executionId;
    private final String status;

    public WorkflowRunResponse(WorkflowExecution execution) {
        this.executionId = execution.getId();
        this.status = execution.getStatus().name();
    }
}
