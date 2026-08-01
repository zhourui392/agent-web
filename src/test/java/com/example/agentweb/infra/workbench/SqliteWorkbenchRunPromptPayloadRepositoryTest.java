package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench Run 私有 Prompt 的一对一、insert-only SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchRunPromptPayloadRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private WorkbenchRunPromptPayload payload;
    private SqliteWorkbenchRunPromptPayloadRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("run-prompt.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "prompt-workspace-snapshot");
        Workbench workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-prompt");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        payload = WorkbenchRunPromptPayload.freeze(
                "prompt-run", "exact private workbench prompt",
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                WorkbenchPersistenceFixtures.NOW.plusSeconds(10));
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                payload.getRunId(), workbench.getId(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submission-prompt-run", WorkbenchPersistenceFixtures.HASH_F,
                RunMode.DISCUSS_READ_ONLY, workspace.scope(),
                workspace.snapshot().reference(),
                WorkbenchPersistenceFixtures.capabilityBinding(), null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        CanonicalHashing.sha256("question"), 8)),
                payload.getPromptHash(),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", workspace.scope().getScopeHash(),
                        workspace.scope().getPrimaryRepositoryKey(),
                        1800L, 8388608L),
                null, payload.getCreatedAt());
        new SqliteWorkbenchRunSnapshotRepository(jdbc).add(snapshot);
        repository = new SqliteWorkbenchRunPromptPayloadRepository(jdbc);
    }

    @Test
    void shouldRoundTripExactPrivatePromptAndRejectDuplicate() {
        repository.add(payload);

        WorkbenchRunPromptPayload restored = repository
                .findByRunId(payload.getRunId())
                .orElseThrow(AssertionError::new);
        assertEquals(payload.getRunId(), restored.getRunId());
        assertEquals(payload.getFinalPrompt(), restored.getFinalPrompt());
        assertEquals(payload.getPromptHash(), restored.getPromptHash());
        assertEquals(payload.getHistoryDelivery(),
                restored.getHistoryDelivery());
        assertEquals(payload.getCreatedAt(), restored.getCreatedAt());
        assertThrows(IllegalStateException.class,
                () -> repository.add(payload));
        assertFalse(repository.findByRunId("missing-run").isPresent());
    }

    @Test
    void schemaShouldRejectOrphanAndRestoreShouldFailClosedOnCorruption() {
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workbench_run_prompt_payload "
                        + "(run_id, final_prompt, prompt_hash, "
                        + "history_delivery, created_at) VALUES (?,?,?,?,?)",
                "orphan-run", "prompt", CanonicalHashing.sha256("prompt"),
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX.name(),
                payload.getCreatedAt().toEpochMilli()));
        repository.add(payload);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
                statement.execute("PRAGMA ignore_check_constraints = ON");
                statement.executeUpdate(
                        "UPDATE workbench_run_prompt_payload "
                                + "SET prompt_hash='"
                                + WorkbenchPersistenceFixtures.HASH_A
                                + "' WHERE run_id='prompt-run'");
                statement.execute("PRAGMA ignore_check_constraints = OFF");
                statement.execute("PRAGMA foreign_keys = ON");
            }
            return null;
        });

        assertThrows(IllegalStateException.class,
                () -> repository.findByRunId(payload.getRunId()));
    }
}
