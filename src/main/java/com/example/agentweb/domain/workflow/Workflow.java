package com.example.agentweb.domain.workflow;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工作流定义聚合根。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class Workflow {

    private final String id;
    private final String name;
    private final String description;
    private final AgentType agentType;
    private final String workingDir;
    private final List<WorkflowStep> steps;
    private final boolean enabled;
    private final String createdBy;
    private final Instant createdAt;
    private final Instant updatedAt;

    /**
     * 创建工作流定义。
     *
     * @param id 工作流 ID
     * @param name 工作流名称
     * @param description 说明
     * @param agentType Agent 类型
     * @param workingDir 工作目录
     * @param steps 串行步骤
     * @param enabled 是否启用
     * @param createdBy 创建人工号
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public Workflow(String id, String name, String description, AgentType agentType, String workingDir,
                    List<WorkflowStep> steps, boolean enabled, String createdBy,
                    Instant createdAt, Instant updatedAt) {
        validate(name, agentType, workingDir, steps);
        this.id = requireText(id, "工作流 ID 不能为空");
        this.name = name.trim();
        this.description = description;
        this.agentType = agentType;
        this.workingDir = workingDir.trim();
        this.steps = new ArrayList<>(steps);
        this.enabled = enabled;
        this.createdBy = createdBy;
        this.createdAt = requireInstant(createdAt, "创建时间不能为空");
        this.updatedAt = requireInstant(updatedAt, "更新时间不能为空");
    }

    private void validate(String name, AgentType agentType, String workingDir, List<WorkflowStep> steps) {
        requireText(name, "工作流名称不能为空");
        if (agentType == null) {
            throw new IllegalArgumentException("Agent 类型不能为空");
        }
        requireText(workingDir, "工作目录不能为空");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("工作流至少需要一个步骤");
        }
        ensureUniqueStepNames(steps);
    }

    private void ensureUniqueStepNames(List<WorkflowStep> steps) {
        Set<String> names = new HashSet<>();
        for (WorkflowStep step : steps) {
            if (step == null) {
                throw new IllegalArgumentException("工作流步骤不能为空");
            }
            if (!names.add(step.getName())) {
                throw new IllegalArgumentException("工作流步骤名称不能重复: " + step.getName());
            }
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private Instant requireInstant(Instant value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    /** 守护工作流只有在启用状态下才能开始一次执行。 */
    public void requireRunnable() {
        if (!enabled) {
            throw new IllegalStateException("工作流已停用: " + id);
        }
    }
}
