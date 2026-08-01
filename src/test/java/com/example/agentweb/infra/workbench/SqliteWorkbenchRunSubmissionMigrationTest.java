package com.example.agentweb.infra.workbench;

import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Run 提交证明字段的旧库增量迁移契约。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchRunSubmissionMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void initializerShouldAddRequiredProofColumnsAndPhaseScopedUniqueIndexIdempotently()
            throws Exception {
        JdbcTemplate jdbc = jdbc("legacy-workbench-run.db");
        createLegacySnapshotTable(jdbc);
        jdbc.update("INSERT INTO workbench_run_snapshot "
                        + "(run_id, workbench_id, phase, prompt_hash, created_at) "
                        + "VALUES (?,?,?,?,?)",
                "legacy-run", "workbench-legacy", "IMPLEMENT_TEST",
                repeat('0'), 0L);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);

        initializer.init();
        initializer.init();

        Map<String, Map<String, Object>> columns = columns(jdbc);
        assertTrue(columns.containsKey("submission_idempotency_key"));
        assertTrue(columns.containsKey("submission_request_hash"));
        assertTrue(columns.containsKey("attachments_json"));
        assertEquals(1, ((Number) columns.get(
                "submission_idempotency_key").get("notnull")).intValue());
        assertEquals(1, ((Number) columns.get(
                "submission_request_hash").get("notnull")).intValue());
        assertEquals(1, ((Number) columns.get(
                "attachments_json").get("notnull")).intValue());

        Map<String, Object> migratedLegacy = jdbc.queryForMap(
                "SELECT submission_idempotency_key, submission_request_hash, "
                        + "attachments_json "
                        + "FROM workbench_run_snapshot WHERE run_id=?",
                "legacy-run");
        assertEquals("legacy:60ee7dbe5e91227acd7980c4e022893002793e0318a5e09592c7a07bb2b937fd",
                migratedLegacy.get("submission_idempotency_key"));
        assertEquals("b43ee4576517dbf14ca173260a1c4bf354fb97789bd8c1dd27f99e76f661521e",
                migratedLegacy.get("submission_request_hash"));
        assertEquals("[]", migratedLegacy.get("attachments_json"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_run_snapshot "
                        + "WHERE workbench_id=? AND phase=? "
                        + "AND submission_idempotency_key=?",
                Integer.class, "workbench-legacy", "IMPLEMENT_TEST", "client-key"));

        insert(jdbc, "run-1", "workbench-1", "IMPLEMENT_TEST",
                "same-key", repeat('a'));
        insert(jdbc, "run-other-phase", "workbench-1", "REVIEW_REFACTOR",
                "same-key", repeat('b'));
        insert(jdbc, "run-other-workbench", "workbench-2", "IMPLEMENT_TEST",
                "same-key", repeat('c'));

        assertThrows(DataAccessException.class,
                () -> insert(jdbc, "run-conflict", "workbench-1",
                        "IMPLEMENT_TEST", "same-key", repeat('d')));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workbench_run_snapshot "
                        + "(run_id, workbench_id, phase, prompt_hash, created_at) "
                        + "VALUES (?,?,?,?,?)",
                "run-without-proof", "workbench-1", "SOLUTION_DESIGN",
                repeat('0'), 5L));
    }

    private JdbcTemplate jdbc(String databaseName) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve(databaseName).toAbsolutePath());
        return new JdbcTemplate(dataSource);
    }

    private static void createLegacySnapshotTable(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE workbench_run_snapshot ("
                + "run_id TEXT PRIMARY KEY, "
                + "workbench_id TEXT NOT NULL, "
                + "phase TEXT NOT NULL, "
                + "prompt_hash TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL)");
    }

    private static Map<String, Map<String, Object>> columns(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "PRAGMA table_info('workbench_run_snapshot')");
        Map<String, Map<String, Object>> byName =
                new HashMap<String, Map<String, Object>>();
        for (Map<String, Object> row : rows) {
            byName.put(String.valueOf(row.get("name")), row);
        }
        return byName;
    }

    private static void insert(
            JdbcTemplate jdbc, String runId, String workbenchId, String phase,
            String idempotencyKey, String requestHash) {
        jdbc.update("INSERT INTO workbench_run_snapshot "
                        + "(run_id, workbench_id, phase, submission_idempotency_key, "
                        + "submission_request_hash, prompt_hash, created_at) "
                        + "VALUES (?,?,?,?,?,?,?)",
                runId, workbenchId, phase, idempotencyKey, requestHash,
                repeat('0'), 1L);
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
