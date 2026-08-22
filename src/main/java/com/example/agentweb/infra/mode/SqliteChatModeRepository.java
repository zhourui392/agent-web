package com.example.agentweb.infra.mode;

import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ChatModeRepository;
import com.example.agentweb.domain.mode.ModeCapabilities;
import com.example.agentweb.domain.mode.ModeNotFoundException;
import com.example.agentweb.domain.mode.ModePermissionMode;
import com.example.agentweb.domain.shared.DomainText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ChatMode 聚合的 SQLite 写侧仓储。
 *
 * <p>(user_id, identifier) 唯一冲突与乐观版本冲突统一翻译为领域异常；
 * 能力关联表随聚合整体重建（DELETE + INSERT，ON DELETE CASCADE 兜底）。 </p>
 *
 * @author alex
 * @since 2026-08-20
 */
@Repository
@Slf4j
public class SqliteChatModeRepository implements ChatModeRepository {

    /** 模式保存并发编辑/标识冲突的领域语义。 */
    public static final class ModeWriteConflictException extends RuntimeException {
        public ModeWriteConflictException(String message) {
            super(message);
        }
    }

    private final JdbcTemplate jdbc;

    public SqliteChatModeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ChatMode save(ChatMode mode) {
        Integer existingVersion = jdbc.query(
                "SELECT version FROM chat_mode WHERE id = ?",
                (rs, n) -> Integer.valueOf(rs.getInt(1)), mode.getId()).stream().findFirst().orElse(null);
        if (existingVersion == null) {
            insert(mode);
        } else {
            if (existingVersion != mode.getVersion() - 1 && mode.getVersion() > 0) {
                throw new ModeWriteConflictException(
                        "chat mode was concurrently modified: " + mode.getId());
            }
            update(mode);
        }
        replaceCapabilities(mode);
        log.debug("chat-mode-saved modeId={} identifier={} version={}",
                mode.getId(), mode.getIdentifier(), mode.getVersion());
        return mode;
    }

    private void insert(ChatMode mode) {
        try {
            jdbc.update("INSERT INTO chat_mode (id, user_id, identifier, display_name, "
                            + "description, default_prompt, permission_mode, model, effort, "
                            + "source_revision_id, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    mode.getId(), mode.getUserId(), mode.getIdentifier(),
                    mode.getDisplayName(), mode.getDescription(), mode.getDefaultPrompt(),
                    permissionText(mode), mode.getModel(), mode.getEffort(),
                    mode.getSourceRevisionId(), mode.getCreatedAt().toString(),
                    mode.getUpdatedAt().toString(), mode.getVersion());
        } catch (DataAccessException ex) {
            // SQLite 驱动的唯一约束冲突常未被 Spring 翻译为 DataIntegrityViolation，按消息识别
            if (ex.getMessage() != null && ex.getMessage().contains("UNIQUE")) {
                throw new ModeWriteConflictException(
                        "mode identifier already exists for user: " + mode.getIdentifier());
            }
            throw ex;
        }
    }

    private void update(ChatMode mode) {
        int rows = jdbc.update("UPDATE chat_mode SET display_name = ?, description = ?, "
                        + "default_prompt = ?, permission_mode = ?, model = ?, effort = ?, "
                        + "updated_at = ?, version = ? WHERE id = ?",
                mode.getDisplayName(), mode.getDescription(), mode.getDefaultPrompt(),
                permissionText(mode), mode.getModel(), mode.getEffort(),
                mode.getUpdatedAt().toString(), mode.getVersion(), mode.getId());
        if (rows == 0) {
            throw new ModeWriteConflictException("chat mode update lost race: " + mode.getId());
        }
    }

    private void replaceCapabilities(ChatMode mode) {
        jdbc.update("DELETE FROM chat_mode_command WHERE mode_id = ?", mode.getId());
        jdbc.update("DELETE FROM chat_mode_skill WHERE mode_id = ?", mode.getId());
        jdbc.update("DELETE FROM chat_mode_mcp_server WHERE mode_id = ?", mode.getId());
        for (ModeCapabilities.CommandRef ref : mode.getCapabilities().getCommands()) {
            jdbc.update("INSERT INTO chat_mode_command (mode_id, capability_identifier, "
                            + "capability_version, capability_hash, sort_order) VALUES (?, ?, ?, ?, ?)",
                    mode.getId(), ref.getIdentifier(), ref.getVersion(),
                    ref.getContentHash(), ref.getSortOrder());
        }
        for (ModeCapabilities.SkillRef ref : mode.getCapabilities().getSkills()) {
            jdbc.update("INSERT INTO chat_mode_skill (mode_id, capability_identifier, "
                            + "capability_version, capability_hash, sort_order) VALUES (?, ?, ?, ?, ?)",
                    mode.getId(), ref.getIdentifier(), ref.getVersion(),
                    ref.getContentHash(), ref.getSortOrder());
        }
        for (ModeCapabilities.McpServerRef ref : mode.getCapabilities().getMcpServers()) {
            jdbc.update("INSERT INTO chat_mode_mcp_server (mode_id, capability_identifier, "
                            + "capability_version, capability_hash, maximum_access, transport, sort_order) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    mode.getId(), ref.getIdentifier(), ref.getVersion(),
                    ref.getContentHash(), ref.getMaximumAccess(), ref.getTransport(),
                    ref.getSortOrder());
        }
    }

    @Override
    public Optional<ChatMode> find(String id) {
        try {
            ChatMode mode = jdbc.queryForObject(
                    "SELECT id, user_id, identifier, display_name, description, default_prompt, "
                            + "permission_mode, model, effort, source_revision_id, created_at, "
                            + "updated_at, version FROM chat_mode WHERE id = ?",
                    (rs, n) -> mapMode(rs), id);
            return Optional.ofNullable(mode);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public void delete(String id, int expectedVersion) {
        int rows = jdbc.update("DELETE FROM chat_mode WHERE id = ? AND version = ?",
                id, expectedVersion);
        if (rows == 0) {
            // 先确认是否存在, 区分"不存在"与"并发编辑"
            if (find(id).isEmpty()) {
                throw new ModeNotFoundException(id);
            }
            throw new ModeWriteConflictException("chat mode delete lost race: " + id);
        }
        log.info("chat-mode-deleted modeId={}", id);
    }

    private ChatMode mapMode(ResultSet rs) throws SQLException {
        String permission = rs.getString("permission_mode");
        // SQLite 对小整数返回 Integer，禁止直接强转 Long
        long rawSourceRevisionId = rs.getLong("source_revision_id");
        Long resolvedSourceRevisionId =
                rs.wasNull() ? null : Long.valueOf(rawSourceRevisionId);
        return ChatMode.restore(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("identifier"),
                rs.getString("display_name"),
                rs.getString("description"),
                rs.getString("default_prompt"),
                permission == null ? null : ModePermissionMode.fromCliValue(permission),
                rs.getString("model"),
                rs.getString("effort"),
                resolvedSourceRevisionId,
                loadCapabilities(rs.getString("id")),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getInt("version"));
    }

    private ModeCapabilities loadCapabilities(String modeId) {
        List<ModeCapabilities.CommandRef> commands = new ArrayList<ModeCapabilities.CommandRef>();
        List<ModeCapabilities.SkillRef> skills = new ArrayList<ModeCapabilities.SkillRef>();
        List<ModeCapabilities.McpServerRef> mcpServers = new ArrayList<ModeCapabilities.McpServerRef>();
        jdbc.query("SELECT capability_identifier, capability_version, capability_hash, sort_order "
                        + "FROM chat_mode_command WHERE mode_id = ? ORDER BY sort_order, capability_identifier",
                (rs) -> {
                    commands.add(new ModeCapabilities.CommandRef(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
                }, modeId);
        jdbc.query("SELECT capability_identifier, capability_version, capability_hash, sort_order "
                        + "FROM chat_mode_skill WHERE mode_id = ? ORDER BY sort_order, capability_identifier",
                (rs) -> {
                    skills.add(new ModeCapabilities.SkillRef(
                            rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)));
                }, modeId);
        jdbc.query("SELECT capability_identifier, capability_version, capability_hash, "
                        + "maximum_access, transport, sort_order "
                        + "FROM chat_mode_mcp_server WHERE mode_id = ? ORDER BY sort_order, capability_identifier",
                (rs) -> {
                    mcpServers.add(new ModeCapabilities.McpServerRef(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getInt(6)));
                }, modeId);
        return new ModeCapabilities(commands, skills, mcpServers);
    }

    private static String permissionText(ChatMode mode) {
        return mode.getPermissionMode() == null
                ? null : mode.getPermissionMode().cliValue();
    }
}
