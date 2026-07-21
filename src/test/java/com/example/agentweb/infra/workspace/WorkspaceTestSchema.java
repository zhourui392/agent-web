package com.example.agentweb.infra.workspace;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轻集成测试建表工具：与 schema.sql 的 requirement_workspace/port_lease DDL 保持一致。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
final class WorkspaceTestSchema {

    private WorkspaceTestSchema() {
    }

    static void create(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS requirement_workspace ("
                + "id TEXT PRIMARY KEY,"
                + "requirement_id TEXT NOT NULL,"
                + "repo_url TEXT NOT NULL,"
                + "mirror_path TEXT NOT NULL,"
                + "worktree_path TEXT NOT NULL,"
                + "branch TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "ttl_hours INTEGER NOT NULL,"
                + "last_active_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS port_lease ("
                + "port INTEGER PRIMARY KEY,"
                + "workspace_id TEXT NOT NULL,"
                + "leased_at INTEGER NOT NULL)");
    }
}
