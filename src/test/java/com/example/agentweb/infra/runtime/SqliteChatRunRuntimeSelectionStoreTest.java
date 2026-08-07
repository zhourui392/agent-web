package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.shared.AgentType;
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
 * Run 所属 RuntimeSelection 的 SQLite 非秘密快照测试。
 *
 * @author alex
 * @since 2026-08-07
 */
class SqliteChatRunRuntimeSelectionStoreTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-07T12:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteChatRunRuntimeSelectionStore store;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("runtime-selection.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        store = new SqliteChatRunRuntimeSelectionStore(jdbc);
    }

    @Test
    void saveAndFindShouldRoundTripFrozenSelectionWithoutSecretColumns() {
        insertRun("run-1");
        RuntimeSelection selection = new RuntimeSelection(
                "native-prod", AgentType.NATIVE, "https://provider.example/v1",
                "model-a", "high", "prod", RuntimeVersionPolicy.exact("1.2.3"));

        store.save(ChatRunId.of("run-1"), selection);

        RuntimeSelection restored = store.find(ChatRunId.of("run-1")).orElseThrow();
        assertEquals("native-prod", restored.getProfileId());
        assertEquals(AgentType.NATIVE, restored.getAgentType());
        assertEquals("https://provider.example/v1", restored.getEndpoint());
        assertEquals("model-a", restored.getModel());
        assertEquals("high", restored.getReasoningEffort());
        assertEquals("prod", restored.getRuntimeEnvironment());
        assertEquals(RuntimeVersionPolicy.exact("1.2.3"),
                restored.getRuntimeVersionPolicy());
        assertFalse(tableColumns().stream().anyMatch(this::isSecretColumn));
    }

    @Test
    void saveShouldFreezeSelectionAndCascadeWithOwningRun() {
        insertRun("run-1");
        ChatRunId runId = ChatRunId.of("run-1");
        RuntimeSelection original = new RuntimeSelection(
                "codex-a", AgentType.CODEX, "https://first.example/v1",
                "model-a", "medium", null, RuntimeVersionPolicy.configured());
        store.save(runId, original);
        store.save(runId, original);

        RuntimeSelection replacement = new RuntimeSelection(
                "codex-b", AgentType.CODEX, "https://second.example/v1",
                "model-b", "low", null, RuntimeVersionPolicy.configured());
        assertThrows(IllegalStateException.class, () -> store.save(runId, replacement));

        RuntimeSelection restored = store.find(runId).orElseThrow();
        assertEquals("codex-a", restored.getProfileId());
        assertEquals("https://first.example/v1", restored.getEndpoint());
        assertEquals(RuntimeVersionPolicy.configured(), restored.getRuntimeVersionPolicy());
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_run_runtime_selection", Integer.class));

        jdbc.update("DELETE FROM chat_run WHERE id=?", "run-1");

        assertTrue(store.find(runId).isEmpty());
    }

    private void insertRun(String runId) {
        jdbc.update("INSERT INTO chat_run (id, session_id, user_message_id, "
                        + "idempotency_key, recall_enabled, run_origin, status, "
                        + "last_event_seq, created_at, updated_at, version) "
                        + "VALUES (?, ?, 1, ?, 0, 'CHAT', 'PENDING', 0, ?, ?, 0)",
                runId, "session-" + runId, "key-" + runId,
                CREATED_AT.toEpochMilli(), CREATED_AT.toEpochMilli());
    }

    private List<String> tableColumns() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "PRAGMA table_info('chat_run_runtime_selection')");
        return rows.stream().map(row -> String.valueOf(row.get("name"))).toList();
    }

    private boolean isSecretColumn(String column) {
        String normalized = column.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("api_key") || normalized.contains("credential")
                || normalized.contains("secret");
    }
}
