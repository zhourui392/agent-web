package com.example.agentweb.app.harness;

import java.util.Optional;

/**
 * Harness 管理详情 CQRS 读模型端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessRunQueryService {

    Optional<HarnessRunView> findById(String runId);
}
