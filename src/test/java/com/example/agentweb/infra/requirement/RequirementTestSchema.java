package com.example.agentweb.infra.requirement;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轻集成测试建表工具：与 schema.sql 的 requirement/requirement_event DDL 保持一致。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
final class RequirementTestSchema {

    private RequirementTestSchema() {
    }

    static void create(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS requirement ("
                + "id TEXT PRIMARY KEY,"
                + "title TEXT NOT NULL,"
                + "description TEXT,"
                + "status TEXT NOT NULL,"
                + "status_before_suspend TEXT,"
                + "source_type TEXT NOT NULL,"
                + "source_ref TEXT,"
                + "owner TEXT NOT NULL,"
                + "participants_json TEXT,"
                + "workspace_id TEXT,"
                + "plan_json TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS requirement_event ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "requirement_id TEXT NOT NULL,"
                + "event_type TEXT NOT NULL,"
                + "actor TEXT,"
                + "from_status TEXT,"
                + "to_status TEXT,"
                + "payload_json TEXT,"
                + "created_at INTEGER NOT NULL)");
    }
}
