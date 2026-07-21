package com.example.agentweb.domain.workflow;

import lombok.Getter;

import java.time.Instant;

/**
 * 工作流单个步骤的一次执行记录。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowStepExecution {

    private final String id;
    private final String executionId;
    private final int stepIndex;
    private final String stepName;
    private WorkflowStatus status;
    private final String prompt;
    private String output;
    private String errorMessage;
    private final Instant startedAt;
    private Instant finishedAt;

    /**
     * 创建步骤执行记录。
     *
     * @param id 步骤执行 ID
     * @param executionId 所属执行 ID
     * @param stepIndex 步骤序号
     * @param stepName 步骤名
     * @param status 当前状态
     * @param prompt 实际执行 prompt
     * @param output 输出
     * @param errorMessage 错误信息
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     */
    public WorkflowStepExecution(String id, String executionId, int stepIndex, String stepName,
                                 WorkflowStatus status, String prompt, String output, String errorMessage,
                                 Instant startedAt, Instant finishedAt) {
        this.id = requireText(id, "步骤执行 ID 不能为空");
        this.executionId = requireText(executionId, "执行 ID 不能为空");
        this.stepIndex = stepIndex;
        this.stepName = requireText(stepName, "步骤名称不能为空");
        this.status = status == null ? WorkflowStatus.RUNNING : status;
        this.prompt = requireText(prompt, "步骤 prompt 不能为空");
        this.output = output;
        this.errorMessage = errorMessage;
        this.startedAt = requireInstant(startedAt, "开始时间不能为空");
        this.finishedAt = finishedAt;
    }

    /**
     * 标记步骤成功。
     *
     * @param result 步骤输出
     */
    public void markSucceeded(String result) {
        this.status = WorkflowStatus.SUCCEEDED;
        this.output = result;
        this.errorMessage = null;
        this.finishedAt = Instant.now();
    }

    /**
     * 标记步骤失败。
     *
     * @param message 失败原因
     */
    public void markFailed(String message) {
        this.status = WorkflowStatus.FAILED;
        this.errorMessage = message;
        this.finishedAt = Instant.now();
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
}
