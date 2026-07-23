package com.example.agentweb.domain.harness;

import java.util.Optional;

/**
 * HarnessRun 聚合生命周期写侧 Repository。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface HarnessRunRepository {

    void add(HarnessRun run);

    void update(HarnessRun run);

    Optional<HarnessRun> findById(String runId);

    Optional<HarnessRun> findByCreatorAndIdempotencyKey(String createdBy, String idempotencyKey);
}
