package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserAccountRepository;
import com.example.agentweb.domain.auth.UserRole;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * SQLite 用户账户仓储实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Repository
public class SqliteUserAccountRepository implements UserAccountRepository {

    private final JdbcTemplate jdbc;

    public SqliteUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Optional.empty();
        }
        return queryOne("SELECT id, username, password_hash, role, enabled, created_at, updated_at "
                + "FROM user_account WHERE username = ? COLLATE NOCASE", username.trim());
    }

    @Override
    public Optional<UserAccount> findById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return Optional.empty();
        }
        return queryOne("SELECT id, username, password_hash, role, enabled, created_at, updated_at "
                + "FROM user_account WHERE id = ?", id.trim());
    }

    @Override
    public void save(UserAccount account) {
        jdbc.update("INSERT INTO user_account(id, username, password_hash, role, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(id) DO UPDATE SET username=excluded.username, "
                        + "password_hash=excluded.password_hash, role=excluded.role, "
                        + "enabled=excluded.enabled, updated_at=excluded.updated_at",
                account.getId(), account.getUsername(), account.getPasswordHash(), account.getRole().name(),
                account.isEnabled() ? 1 : 0, account.getCreatedAt().toEpochMilli(),
                account.getUpdatedAt().toEpochMilli());
    }

    private Optional<UserAccount> queryOne(String sql, String key) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, this::mapRow, key));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private UserAccount mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        return UserAccount.restore(
                resultSet.getString("id"),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                UserRole.valueOf(resultSet.getString("role")),
                resultSet.getInt("enabled") == 1,
                Instant.ofEpochMilli(resultSet.getLong("created_at")),
                Instant.ofEpochMilli(resultSet.getLong("updated_at")));
    }
}
