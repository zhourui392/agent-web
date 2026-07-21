package com.example.agentweb.app.workflow;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.WorkflowStep;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流创建 / 更新命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowCreateCommand {

    private final String name;
    private final String description;
    private final AgentType agentType;
    private final String workingDir;
    private final List<WorkflowStep> steps;
    private final boolean enabled;

    /**
     * 创建工作流保存命令。
     *
     * @param name 名称
     * @param description 说明
     * @param agentType Agent 类型
     * @param workingDir 工作目录
     * @param steps 步骤列表
     * @param enabled 是否启用
     */
    public WorkflowCreateCommand(String name, String description, AgentType agentType, String workingDir,
                                 List<WorkflowStep> steps, boolean enabled) {
        this.name = name;
        this.description = description;
        this.agentType = agentType == null ? AgentType.CODEX : agentType;
        this.workingDir = workingDir;
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        this.enabled = enabled;
    }
}
