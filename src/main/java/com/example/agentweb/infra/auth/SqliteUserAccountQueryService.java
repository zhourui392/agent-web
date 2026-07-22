package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AdminUserView;
import com.example.agentweb.app.auth.UserAccountQueryService;
import com.example.agentweb.domain.auth.UserRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 管理后台用户列表的 SQLite 读侧实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class SqliteUserAccountQueryService implements UserAccountQueryService {

    private final JdbcTemplate jdbc;

    public SqliteUserAccountQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AdminUserView> listAll() {
        return jdbc.query(
                "SELECT id, username, role, enabled, created_at, updated_at "
                        + "FROM user_account ORDER BY created_at DESC, username COLLATE NOCASE ASC",
                (resultSet, rowNum) -> new AdminUserView(
                        resultSet.getString("id"),
                        resultSet.getString("username"),
                        UserRole.valueOf(resultSet.getString("role")),
                        resultSet.getInt("enabled") == 1,
                        Instant.ofEpochMilli(resultSet.getLong("created_at")),
                        Instant.ofEpochMilli(resultSet.getLong("updated_at"))));
    }
}
