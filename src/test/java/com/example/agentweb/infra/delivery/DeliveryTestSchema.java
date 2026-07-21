package com.example.agentweb.infra.delivery;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 轻集成测试建表工具:与 schema.sql 的 merge_request_ref/processed_webhook/requirement_intake_dedup DDL 保持一致。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
final class DeliveryTestSchema {

    private DeliveryTestSchema() {
    }

    static void create(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS merge_request_ref ("
                + "id              INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "requirement_id  TEXT    NOT NULL,"
                + "mr_iid          INTEGER NOT NULL,"
                + "mr_url          TEXT    NOT NULL,"
                + "draft           INTEGER NOT NULL,"
                + "pipeline_status TEXT,"
                + "updated_at      INTEGER NOT NULL,"
                + "UNIQUE (requirement_id, mr_iid))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS processed_webhook ("
                + "event_uuid  TEXT PRIMARY KEY,"
                + "received_at INTEGER NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS requirement_intake_dedup ("
                + "api_key_name   TEXT    NOT NULL,"
                + "idem_key       TEXT    NOT NULL,"
                + "requirement_id TEXT    NOT NULL,"
                + "created_at     INTEGER NOT NULL,"
                + "PRIMARY KEY (api_key_name, idem_key))");
    }
}
