package com.example.agentweb.infra;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code chat_session} 中性会话字段的新库约束与旧库增量迁移测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteSessionKindMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void existingDatabaseShouldAddColumnsAndRestoreLegacyRowsAsChat() throws Exception {
        JdbcTemplate jdbc = jdbc("legacy-session.db");
        jdbc.execute("CREATE TABLE chat_session (id TEXT PRIMARY KEY, agent_type TEXT NOT NULL, "
                + "working_dir TEXT NOT NULL, created_at TEXT NOT NULL)");
        jdbc.update("INSERT INTO chat_session(id, agent_type, working_dir, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                "legacy-1", "CLAUDE", "/legacy", "2026-07-01T00:00:00Z");

        new SqliteInitializer(jdbc).init();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT session_kind, context_id, retired_at FROM chat_session WHERE id = ?",
                "legacy-1");
        assertEquals("CHAT", row.get("session_kind"));
        assertNull(row.get("context_id"));
        assertNull(row.get("retired_at"));

        SqliteSessionRepo repository = new SqliteSessionRepo(
                jdbc, new CurrentUserProvider(() -> Optional.empty()));
        ChatSession restored = repository.findById("legacy-1");
        assertEquals(SessionKind.CHAT, restored.getSessionKind());
        assertNull(restored.getContextId());
        assertNull(restored.getRetiredAt());
    }

    @Test
    void newDatabaseShouldEnforceKindAndContextConsistency() throws Exception {
        JdbcTemplate jdbc = jdbc("new-session.db");
        new SqliteInitializer(jdbc).init();

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO chat_session(id, agent_type, working_dir, created_at, "
                        + "session_kind, context_id) VALUES (?, ?, ?, ?, ?, ?)",
                "invalid-chat", "CODEX", "/workspace", Instant.EPOCH.toString(),
                "CHAT", "workbench-1:IMPLEMENT_TEST"));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO chat_session(id, agent_type, working_dir, created_at, "
                        + "session_kind, context_id) VALUES (?, ?, ?, ?, ?, ?)",
                "invalid-phase", "CODEX", "/workspace", Instant.EPOCH.toString(),
                "WORKBENCH_PHASE", null));
    }

    private JdbcTemplate jdbc(String fileName) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(fileName));
        return new JdbcTemplate(dataSource);
    }
}
