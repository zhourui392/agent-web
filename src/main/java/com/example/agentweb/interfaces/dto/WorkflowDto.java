package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.workflow.Workflow;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流定义响应。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowDto {

    private final String id;
    private final String name;
    private final String description;
    private final String agentType;
    private final String workingDir;
    private final List<WorkflowStepDto> steps;
    private final boolean enabled;
    private final String createdBy;
    private final String createdAt;
    private final String updatedAt;

    public WorkflowDto(Workflow workflow) {
        this.id = workflow.getId();
        this.name = workflow.getName();
        this.description = workflow.getDescription();
        this.agentType = workflow.getAgentType().name();
        this.workingDir = workflow.getWorkingDir();
        this.steps = workflow.getSteps().stream()
                .map(WorkflowStepDto::from)
                .collect(Collectors.toList());
        this.enabled = workflow.isEnabled();
        this.createdBy = workflow.getCreatedBy();
        this.createdAt = workflow.getCreatedAt().toString();
        this.updatedAt = workflow.getUpdatedAt().toString();
    }

    /**
     * 领域对象转 DTO。
     *
     * @param workflow 工作流定义
     * @return DTO
     */
    public static WorkflowDto from(Workflow workflow) {
        return workflow == null ? null : new WorkflowDto(workflow);
    }
}
