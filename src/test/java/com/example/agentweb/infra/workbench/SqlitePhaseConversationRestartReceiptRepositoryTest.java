package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase Conversation restart receipt 的真实 SQLite 持久化与 additive 建表测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqlitePhaseConversationRestartReceiptRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqlitePhaseConversationRestartReceiptRepository repository;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("conversation-receipt.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "conversation-receipt-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-conversation-receipt");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        insertPhaseSession("session-0");
        insertPhaseSession("session-1");
        insertPhaseSession("session-2");
        repository = new SqlitePhaseConversationRestartReceiptRepository(jdbc);
    }

    @Test
    void addAndFindShouldRoundTripReceiptWithoutPathOrSecretColumns() {
        PhaseConversationRestartReceipt receipt = receipt("restart-key-1", "session-1");

        repository.add(receipt);

        PhaseConversationRestartReceipt restored = repository
                .findByOwnerAndIdempotencyKey(OWNER, "restart-key-1")
                .orElseThrow(AssertionError::new);
        assertEquals(receipt, restored);
        assertFalse(repository.findByOwnerAndIdempotencyKey(
                OwnerReference.of("foreign", "Other"), "restart-key-1").isPresent());
        List<Map<String, Object>> columns = jdbc.queryForList(
                "PRAGMA table_info(workbench_phase_conversation_restart_receipt)");
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name")).toLowerCase();
            assertFalse(name.contains("path"));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("token"));
        }
    }

    @Test
    void ownerAndIdempotencyKeyShouldBeUnique() {
        repository.add(receipt("restart-key-1", "session-1"));

        assertThrows(DataAccessException.class,
                () -> repository.add(receipt("restart-key-1", "session-2")));

        assertEquals("session-1", repository
                .findByOwnerAndIdempotencyKey(OWNER, "restart-key-1")
                .orElseThrow(AssertionError::new)
                .getSessionId());
    }

    @Test
    void findShouldFailClosedWhenReceiptOwnerDoesNotMatchReferencedWorkbenchOwner() {
        repository.add(receipt("restart-key-1", "session-1"));
        jdbc.update("UPDATE workbench SET owner_id='corrupt-owner', owner_name='Corrupt' "
                + "WHERE id=?", workbench.getId().getValue());

        assertThrows(IllegalStateException.class,
                () -> repository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"));
    }

    @Test
    void initializerShouldAddReceiptTableToExistingDatabaseWithoutDroppingLegacyData()
            throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("legacy-conversation.db"));
        JdbcTemplate legacy = new JdbcTemplate(dataSource);
        legacy.execute("CREATE TABLE legacy_marker(id TEXT PRIMARY KEY)");
        legacy.update("INSERT INTO legacy_marker(id) VALUES ('keep-me')");

        SqliteInitializer initializer = new SqliteInitializer(legacy);
        initializer.init();
        initializer.init();

        assertEquals("keep-me", legacy.queryForObject(
                "SELECT id FROM legacy_marker", String.class));
        Integer tableCount = legacy.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' "
                        + "AND name='workbench_phase_conversation_restart_receipt'",
                Integer.class);
        assertEquals(1, tableCount.intValue());
    }

    private PhaseConversationRestartReceipt receipt(String key, String sessionId) {
        return PhaseConversationRestartReceipt.record(
                OWNER, key, workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST,
                "session-0", sessionId, 1, 4L,
                Instant.parse("2026-08-01T10:00:00Z"));
    }

    private void insertPhaseSession(String sessionId) {
        jdbc.update("INSERT INTO chat_session (id, agent_type, working_dir, created_at, "
                        + "session_kind, context_id) VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, "CODEX", "/workspace/agent-web",
                "2026-08-01T09:00:00Z", "WORKBENCH_PHASE",
                workbench.getId().getValue() + ":IMPLEMENT_TEST");
    }
}
