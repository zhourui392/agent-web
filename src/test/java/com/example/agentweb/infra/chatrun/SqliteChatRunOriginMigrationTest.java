package com.example.agentweb.infra.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ChatRun 来源字段的 additive migration 兼容性测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteChatRunOriginMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void initializerShouldMigrateLegacyRowsToChatOriginIdempotently() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("legacy-chat-run.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createLegacyTable(jdbc);
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, "
                        + "idempotency_key, recall_enabled, status, last_event_seq, "
                        + "created_at, updated_at, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                "legacy-run", "legacy-session", 1L, "legacy-key", 1,
                "PENDING", 0L, 1L, 1L, 0L);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);

        initializer.init();
        initializer.init();

        ChatRun restored = new SqliteChatRunRepository(jdbc)
                .findById(ChatRunId.of("legacy-run"))
                .orElseThrow(AssertionError::new);
        assertEquals(RunOrigin.CHAT, restored.getRunOrigin());
        assertFalse(restored.getExecutionContextReference().isPresent());
    }

    private static void createLegacyTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE chat_run (id TEXT PRIMARY KEY, "
                + "session_id TEXT NOT NULL, user_message_id INTEGER NOT NULL, "
                + "assistant_message_id INTEGER, idempotency_key TEXT NOT NULL, "
                + "recall_enabled INTEGER NOT NULL DEFAULT 1, status TEXT NOT NULL, "
                + "last_event_seq INTEGER NOT NULL DEFAULT 0, exit_code INTEGER, "
                + "failure_code TEXT, error_message TEXT, created_at INTEGER NOT NULL, "
                + "started_at INTEGER, cancel_requested_at INTEGER, finished_at INTEGER, "
                + "updated_at INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 0, "
                + "UNIQUE(session_id, idempotency_key), UNIQUE(assistant_message_id))");
    }
}
