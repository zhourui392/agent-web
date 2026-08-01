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

    /**
     * 按 Owner 与提交身份查找可安全公开的快速重放候选。
     * 外部 Owner 与不存在的候选必须具有相同的空结果语义。
     */
    Optional<WorkbenchRunSnapshot> findReplayCandidate(
            OwnerReference owner, WorkbenchId workbenchId,
            WorkbenchPhase phase, String submissionIdempotencyKey);

    Optional<WorkbenchRunSnapshot> findByWorkbenchPhaseAndIdempotencyKey(
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String submissionIdempotencyKey);
}
