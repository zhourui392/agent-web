package com.example.agentweb.domain.workflow;

import java.util.List;

/**
 * 工作流执行记录持久化端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
public interface WorkflowExecutionRepository {

    /**
     * 保存执行记录。
     *
     * @param execution 执行记录
     */
    void save(WorkflowExecution execution);

    /**
     * 更新执行记录。
     *
     * @param execution 执行记录
     */
    void update(WorkflowExecution execution);

    /**
     * 按 ID 查询执行记录。
     *
     * @param id 执行 ID
     * @return 执行记录,不存在返回 null
     */
    WorkflowExecution findById(String id);

    /**
     * 分页查询执行记录。
     *
     * @param offset 起始偏移
     * @param limit 返回条数
     * @return 执行记录列表
     */
    List<WorkflowExecution> findAll(int offset, int limit);

    /**
     * 统计全部执行记录。
     *
     * @return 记录数
     */
    long countAll();

    /**
     * 按工作流查询执行记录。
     *
     * @param workflowId 工作流 ID
     * @param offset 起始偏移
     * @param limit 返回条数
     * @return 执行记录列表
     */
    List<WorkflowExecution> findByWorkflowId(String workflowId, int offset, int limit);

    /**
     * 按工作流统计执行记录。
     *
     * @param workflowId 工作流 ID
     * @return 记录数
     */
    long countByWorkflowId(String workflowId);

    /**
     * 保存步骤执行记录。
     *
     * @param stepExecution 步骤执行记录
     */
    void saveStep(WorkflowStepExecution stepExecution);

    /**
     * 更新步骤执行记录。
     *
     * @param stepExecution 步骤执行记录
     */
    void updateStep(WorkflowStepExecution stepExecution);

    /**
     * 查询一个执行下的步骤记录。
     *
     * @param executionId 执行 ID
     * @return 步骤记录列表
     */
    List<WorkflowStepExecution> findStepsByExecutionId(String executionId);
}
