package com.example.agentweb.infra.setting;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * app_setting 表的 SQLite 实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
@Repository
@Slf4j
public class SqliteAppSettingRepo implements AppSettingRepository {

    private final JdbcTemplate jdbc;

    public SqliteAppSettingRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> get(String key) {
        List<String> rows = jdbc.query(
                "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                (rs, rowNum) -> rs.getString("setting_value"),
                key
        );
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    @Override
    public void put(String key, String value, long updatedAtMillis) {
        int rows = jdbc.update(
                "INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT(setting_key) DO UPDATE SET"
                        + " setting_value = excluded.setting_value, updated_at = excluded.updated_at",
                key, value, updatedAtMillis
        );
        log.debug("app-setting-put key={} affectedRows={}", key, rows);
    }
}
