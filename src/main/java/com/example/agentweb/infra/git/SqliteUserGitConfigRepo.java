package com.example.agentweb.infra.git;

import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code user_git_config} 表的 SQLite 实现。协议适配，无业务判断。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Repository
@Slf4j
public class SqliteUserGitConfigRepo implements UserGitConfigRepository {

    private static final String SELECT_COLUMNS =
            "user_id, git_name, git_email, cred_username, cred_password_enc, updated_at";

    private final JdbcTemplate jdbc;

    public SqliteUserGitConfigRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<UserGitConfig> ROW_MAPPER = new RowMapper<UserGitConfig>() {
        @Override
        public UserGitConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            String userId = rs.getString("user_id");
            String name = rs.getString("git_name");
            String email = rs.getString("git_email");
            GitIdentity identity = (name != null && !name.isEmpty() && email != null && !email.isEmpty())
                    ? GitIdentity.of(name, email) : null;
            long updatedMillis = rs.getLong("updated_at");
            Instant updatedAt = rs.wasNull() ? null : Instant.ofEpochMilli(updatedMillis);
            return UserGitConfig.restore(userId, identity,
                    rs.getString("cred_username"), rs.getString("cred_password_enc"), updatedAt);
        }
    };

    @Override
    public Optional<UserGitConfig> findByUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return Optional.empty();
        }
        List<UserGitConfig> rows = jdbc.query(
                "SELECT " + SELECT_COLUMNS + " FROM user_git_config WHERE user_id = ?",
                ROW_MAPPER, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void save(UserGitConfig config) {
        GitIdentity identity = config.getIdentity();
        Long updatedAt = config.getUpdatedAt() == null ? null : config.getUpdatedAt().toEpochMilli();
        int rows = jdbc.update(
                "INSERT OR REPLACE INTO user_git_config"
                        + " (user_id, git_name, git_email, cred_username, cred_password_enc, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                config.getUserId(),
                identity == null ? null : identity.getName(),
                identity == null ? null : identity.getEmail(),
                config.getCredentialUsername(),
                config.getCredentialPasswordCipher(),
                updatedAt);
        log.debug("user-git-config-saved userId={} hasCredential={} affectedRows={}",
                config.getUserId(), config.hasCredential(), rows);
    }

    @Override
    public void deleteByUserId(String userId) {
        int rows = jdbc.update("DELETE FROM user_git_config WHERE user_id = ?", userId);
        log.debug("user-git-config-deleted userId={} affectedRows={}", userId, rows);
    }
}
