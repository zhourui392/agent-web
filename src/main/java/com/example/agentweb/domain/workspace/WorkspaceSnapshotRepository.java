package com.example.agentweb.domain.workspace;

import java.util.Optional;

/**
 * 不可变 Workspace Snapshot 聚合的写侧生命周期 Repository。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkspaceSnapshotRepository {

    void add(WorkspaceSnapshot snapshot);

    Optional<WorkspaceSnapshot> findById(String snapshotId);
}
