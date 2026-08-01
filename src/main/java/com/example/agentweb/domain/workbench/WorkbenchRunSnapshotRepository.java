package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 不可变 Workbench Run Snapshot 的写侧 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkbenchRunSnapshotRepository {

    void add(WorkbenchRunSnapshot snapshot);

    Optional<WorkbenchRunSnapshot> findByRunId(String runId);

    Optional<WorkbenchRunSnapshot> findByWorkbenchPhaseAndIdempotencyKey(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey);
}
