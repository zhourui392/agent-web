package com.example.agentweb.app.requirement;

import java.time.Instant;
import java.util.Optional;

/**
 * 外部系统建需求的幂等去重(requirement_intake_dedup 表),对齐 diagnose API 的
 * (apiKeyName, Idempotency-Key) 二元组模式。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface RequirementIdempotencyStore {

    /** 命中返回既有需求 ID */
    Optional<String> findRequirementId(String apiKeyName, String idempotencyKey);

    void record(String apiKeyName, String idempotencyKey, String requirementId, Instant at);
}
