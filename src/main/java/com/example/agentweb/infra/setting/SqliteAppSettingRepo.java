package com.example.agentweb.infra.setting;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * app_setting 表的 SQLite 实现。读操作按 key 缓存，写成功后刷新缓存，删除成功后淘汰缓存。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-25
 */
@Repository
@Slf4j
public class SqliteAppSettingRepo implements AppSettingRepository {

    private final JdbcTemplate jdbc;
    private final Cache cache;

    public SqliteAppSettingRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.cache = new ConcurrentMapCache("app-setting");
    }

    @Override
    public Optional<String> get(String key) {
        String value = cache.get(key, () -> load(key));
        return Optional.ofNullable(value);
    }

    @Override
    public void put(String key, String value, long updatedAtMillis) {
        int rows = jdbc.update(
                "INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, ?)"
                        + " ON CONFLICT(setting_key) DO UPDATE SET"
                        + " setting_value = excluded.setting_value, updated_at = excluded.updated_at",
                key, value, updatedAtMillis
        );
        cache.put(key, value);
        log.debug("app-setting-put key={} affectedRows={}", key, rows);
    }

    @Override
    public void delete(String key) {
        int rows = jdbc.update("DELETE FROM app_setting WHERE setting_key = ?", key);
        cache.evict(key);
        log.debug("app-setting-delete key={} affectedRows={}", key, rows);
    }

    private String load(String key) {
        List<String> rows = jdbc.query(
                "SELECT setting_value FROM app_setting WHERE setting_key = ?",
                (rs, rowNum) -> rs.getString("setting_value"),
                key
        );
        return rows.isEmpty() ? null : rows.get(0);
    }
}
