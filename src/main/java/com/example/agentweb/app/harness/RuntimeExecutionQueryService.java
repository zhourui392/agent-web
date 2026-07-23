package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;

import java.util.Optional;

/**
 * RuntimeExecution 管理读模型查询端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface RuntimeExecutionQueryService {

    /**
     * 按 Attempt 查询脱敏执行视图。
     *
     * @param runId Run ID
     * @param stage 阶段
     * @param attemptNumber Attempt 序号
     * @return 可选执行视图
     */
    Optional<RuntimeExecutionView> find(String runId, HarnessStage stage, int attemptNumber);
}
