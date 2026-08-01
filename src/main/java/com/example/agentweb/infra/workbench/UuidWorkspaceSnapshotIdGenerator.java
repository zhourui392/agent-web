package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.WorkspaceSnapshotIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 使用完整 UUID 生成 Workspace Snapshot 标识的无状态适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class UuidWorkspaceSnapshotIdGenerator
        implements WorkspaceSnapshotIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
