package com.example.agentweb.domain.workflow;

import lombok.Getter;

import java.time.Instant;

/**
 * 工作流一次运行的执行记录。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
public class WorkflowExecution {

    private final String id;
    private final String workflowId;
    private WorkflowStatus status;
    private final String inputsJson;
    private final Instant startedAt;
    private Instant finishedAt;
    private String errorMessage;
    private final String createdBy;

    /**
     * 创建执行记录。
     *
     * @param id 执行 ID
     * @param workflowId 工作流 ID
     * @param status 当前状态
     * @param inputsJson 运行输入 JSON
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param errorMessage 错误信息
     * @param createdBy 触发人工号
     */
    public WorkflowExecution(String id, String workflowId, WorkflowStatus status, String inputsJson,
                             Instant startedAt, Instant finishedAt, String errorMessage, String createdBy) {
        this.id = requireText(id, "执行 ID 不能为空");
        this.workflowId = requireText(workflowId, "工作流 ID 不能为空");
        this.status = status == null ? WorkflowStatus.RUNNING : status;
        this.inputsJson = inputsJson;
        this.startedAt = requireInstant(startedAt, "开始时间不能为空");
        this.finishedAt = finishedAt;
        this.errorMessage = errorMessage;
        this.createdBy = createdBy;
    }

    /**
     * 标记执行成功。
     */
    public void markSucceeded() {
        this.status = WorkflowStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.errorMessage = null;
    }

    /**
     * 标记执行失败。
     *
     * @param message 失败原因
     */
    public void markFailed(String message) {
        this.status = WorkflowStatus.FAILED;
        this.finishedAt = Instant.now();
        this.errorMessage = message;
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
