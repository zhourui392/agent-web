package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessStage;

import java.util.Optional;

/**
 * Capability Snapshot CQRS 读模型端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface CapabilitySnapshotQueryService {

    Optional<CapabilitySnapshotView> find(String runId, HarnessStage stage, int attemptNumber);
}
