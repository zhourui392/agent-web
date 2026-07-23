package com.example.agentweb.domain.harness;

import java.util.Optional;

/**
 * RuntimeExecution 聚合生命周期写侧端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface RuntimeExecutionRepository {

    /**
     * 新增执行聚合。
     *
     * @param execution 新执行聚合
     */
    void add(RuntimeExecution execution);

    /**
     * 更新执行聚合。
     *
     * @param execution 已存在的执行聚合
     */
    void update(RuntimeExecution execution);

    /**
     * 按 ID 查询执行聚合。
     *
     * @param executionId Execution ID
     * @return 可选执行聚合
     */
    Optional<RuntimeExecution> findById(String executionId);

    /**
     * 按 Attempt 查询执行聚合。
     *
     * @param runId Run ID
     * @param stage 阶段
     * @param attemptNumber Attempt 序号
     * @return 可选执行聚合
     */
    Optional<RuntimeExecution> findByAttempt(String runId, HarnessStage stage, int attemptNumber);

    /**
     * 按 Run 内幂等键查询执行聚合。
     *
     * @param runId Run ID
     * @param idempotencyKey 幂等键
     * @return 可选执行聚合
     */
    Optional<RuntimeExecution> findByIdempotencyKey(String runId, String idempotencyKey);

    /**
     * 幂等追加非敏感执行事件。
     *
     * @param event 待追加的非敏感执行事件
     */
    void appendEvent(RuntimeExecutionEvent event);
}
