package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChatRun 与公共 RuntimeHandle 的 SQLite 稳定绑定、唯一性和外键测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteChatRunRuntimeHandleStoreTest {

    private static final Instant BOUND_AT =
            Instant.parse("2026-08-01T13:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private ChatRunRuntimeHandleStore store;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("runtime-handle.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        store = new SqliteChatRunRuntimeHandleStore(jdbc);
    }

    @Test
    void bindFindAndDeleteShouldRoundTripStableHandleIdempotently() {
        insertRun("run-1");
        ChatRunId runId = ChatRunId.of("run-1");
        RuntimeHandle handle = new RuntimeHandle("run-1", "runtime-handle-1");

        store.bind(runId, handle, BOUND_AT);
        store.bind(runId, handle, BOUND_AT.plusSeconds(1));

        assertEquals(handle, store.find(runId).get());
        assertEquals(1, rowCount());
        assertEquals(Long.valueOf(BOUND_AT.toEpochMilli()), jdbc.queryForObject(
                "SELECT bound_at FROM chat_run_runtime_handle WHERE run_id=?",
                Long.class, "run-1"));

        store.delete(runId);
        store.delete(runId);

        assertFalse(store.find(runId).isPresent());
    }

    @Test
    void bindShouldRejectExecutionIdDifferentFromRunIdBeforePersistence() {
        insertRun("run-1");

        assertThrows(IllegalArgumentException.class, () -> store.bind(
                ChatRunId.of("run-1"),
                new RuntimeHandle("another-run", "runtime-handle-1"),
                BOUND_AT));

        assertEquals(0, rowCount());
    }

    @Test
    void sameRunDifferentHandleOrCrossRunSameHandleShouldConflict() {
        insertRun("run-1");
        insertRun("run-2");
        store.bind(ChatRunId.of("run-1"),
                new RuntimeHandle("run-1", "shared-handle"), BOUND_AT);

        assertThrows(IllegalStateException.class, () -> store.bind(
                ChatRunId.of("run-1"),
                new RuntimeHandle("run-1", "different-handle"), BOUND_AT));
        assertThrows(IllegalStateException.class, () -> store.bind(
                ChatRunId.of("run-2"),
                new RuntimeHandle("run-2", "shared-handle"), BOUND_AT));

        assertEquals(1, rowCount());
        assertEquals("shared-handle",
                store.find(ChatRunId.of("run-1")).get().getHandleId());
        assertFalse(store.find(ChatRunId.of("run-2")).isPresent());
    }

    @Test
    void databaseShouldEnforceRunExecutionHandleUniquenessAndForeignKeyCascade() {
        insertRun("run-1");
        insertRun("run-2");
        store.bind(ChatRunId.of("run-1"),
                new RuntimeHandle("run-1", "runtime-handle-1"), BOUND_AT);

        assertTrue(hasUniqueSingleColumnIndex("execution_id"));
        assertTrue(hasUniqueSingleColumnIndex("handle_id"));
        assertTrue(runIdIsPrimaryKey());
        assertTrue(hasRunForeignKey());
        assertThrows(IllegalStateException.class, () -> store.bind(
                ChatRunId.of("missing-run"),
                new RuntimeHandle("missing-run", "runtime-handle-missing"),
                BOUND_AT));

        jdbc.update("DELETE FROM chat_run WHERE id=?", "run-1");

        assertFalse(store.find(ChatRunId.of("run-1")).isPresent());
    }

    @Test
    void initializerShouldCreateRuntimeHandleSchemaIdempotently() throws Exception {
        new SqliteInitializer(jdbc).init();

        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master "
                        + "WHERE type='table' AND name='chat_run_runtime_handle'",
                Integer.class);

        assertEquals(Integer.valueOf(1), tables);
    }

    private void insertRun(String runId) {
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, "
                        + "idempotency_key, recall_enabled, run_origin, status, "
                        + "last_event_seq, created_at, updated_at, version) "
                        + "VALUES (?, ?, 1, ?, 0, 'CHAT', 'PENDING', 0, ?, ?, 0)",
                runId, "session-" + runId, "key-" + runId,
                BOUND_AT.toEpochMilli(), BOUND_AT.toEpochMilli());
    }

    private int rowCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_run_runtime_handle", Integer.class);
        return count == null ? 0 : count.intValue();
    }

    private boolean hasUniqueSingleColumnIndex(String column) {
        List<Map<String, Object>> indexes = jdbc.queryForList(
                "PRAGMA index_list('chat_run_runtime_handle')");
        for (Map<String, Object> index : indexes) {
            if (((Number) index.get("unique")).intValue() != 1) {
                continue;
            }
            String name = String.valueOf(index.get("name"));
            List<Map<String, Object>> columns = jdbc.queryForList(
                    "PRAGMA index_info('" + name + "')");
            if (columns.size() == 1
                    && column.equals(String.valueOf(columns.get(0).get("name")))) {
                return true;
            }
        }
        return false;
    }

    private boolean runIdIsPrimaryKey() {
        List<Map<String, Object>> columns = jdbc.queryForList(
                "PRAGMA table_info('chat_run_runtime_handle')");
        for (Map<String, Object> column : columns) {
            if ("run_id".equals(String.valueOf(column.get("name")))) {
                return ((Number) column.get("pk")).intValue() == 1;
            }
        }
        return false;
    }

    private boolean hasRunForeignKey() {
        List<Map<String, Object>> foreignKeys = jdbc.queryForList(
                "PRAGMA foreign_key_list('chat_run_runtime_handle')");
        for (Map<String, Object> foreignKey : foreignKeys) {
            if ("chat_run".equals(String.valueOf(foreignKey.get("table")))
                    && "run_id".equals(String.valueOf(foreignKey.get("from")))
                    && "id".equals(String.valueOf(foreignKey.get("to")))) {
                return true;
            }
        }
        return false;
    }
}
