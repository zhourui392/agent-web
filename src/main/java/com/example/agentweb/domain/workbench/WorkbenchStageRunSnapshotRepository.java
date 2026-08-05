package com.example.agentweb.domain.workbench;

import java.util.Optional;

/**
 * 动态 Workbench Stage Run 不可变 Snapshot 仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
public interface WorkbenchStageRunSnapshotRepository {

    void add(WorkbenchStageRunSnapshot snapshot);

    Optional<WorkbenchStageRunSnapshot> findByRunId(String runId);

    Optional<WorkbenchStageRunSnapshot> findReplayCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            String stageInstanceIdentifier,
            String submissionIdempotencyKey);

    Optional<WorkbenchStageRunSnapshot>
            findByWorkbenchStageAndIdempotencyKey(
                    WorkbenchId workbenchId,
                    String stageInstanceIdentifier,
                    String submissionIdempotencyKey);
}
