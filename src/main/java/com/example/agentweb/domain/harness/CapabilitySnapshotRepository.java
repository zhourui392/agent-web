package com.example.agentweb.domain.harness;

import java.util.Optional;

/**
 * Capability Snapshot 写侧生命周期端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface CapabilitySnapshotRepository {

    Optional<CapabilitySnapshot> find(String runId, HarnessStage stage, int attemptNumber);

    CapabilitySnapshot saveIfAbsent(CapabilitySnapshot snapshot);
}
