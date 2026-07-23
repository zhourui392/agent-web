package com.example.agentweb.app.harness;

/**
 * Harness RuntimeExecution 启动与取消用例端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessExecutionService {

    /**
     * 准备并在事务提交后启动一次执行。
     *
     * @param command 启动命令
     * @return 执行受理结果
     */
    HarnessExecutionResult start(StartHarnessExecutionCommand command);

    /**
     * 先持久化取消意图，再终止活动 Runtime。
     *
     * @param runId Run ID
     * @param reason 取消原因
     * @return Run 变更结果
     */
    HarnessMutationResult cancel(String runId, String reason);
}
