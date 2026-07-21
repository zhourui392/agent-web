package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流执行详情响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowExecutionDetailResponse extends WorkflowExecutionDto {

    private final WorkflowDto workflow;
    private final List<WorkflowStepExecutionDto> steps;

    public WorkflowExecutionDetailResponse(WorkflowExecution execution, Workflow workflow,
                                           List<WorkflowStepExecution> steps) {
        super(execution);
        this.workflow = WorkflowDto.from(workflow);
        this.steps = steps.stream()
                .map(WorkflowStepExecutionDto::from)
                .collect(Collectors.toList());
    }
}
