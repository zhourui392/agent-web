package com.example.agentweb.app.harness;

/**
 * 当前 local DEPLOYMENT Attempt 准备状态查询端口。
 *
 * @author alex
 * @since 2026-07-24
 */
public interface HarnessDeploymentReadinessQueryService {

    HarnessDeploymentReadinessView find(String runId);
}
