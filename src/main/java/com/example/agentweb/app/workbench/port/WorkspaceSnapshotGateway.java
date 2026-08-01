package com.example.agentweb.app.workbench.port;

import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;

/**
 * 在受信 Repository Scope 内采集稳定 Git Snapshot 的端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface WorkspaceSnapshotGateway {

    WorkspaceSnapshot capture(String snapshotId, RepositoryScope scope, SnapshotPurpose purpose);
}
