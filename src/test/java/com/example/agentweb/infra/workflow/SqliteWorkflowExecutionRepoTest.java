package com.example.agentweb.infra.workflow;

import com.example.agentweb.domain.workflow.WorkflowExecution;
import com.example.agentweb.domain.workflow.WorkflowStatus;
import com.example.agentweb.domain.workflow.WorkflowStepExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
class SqliteWorkflowExecutionRepoTest {

    @TempDir
    Path tempDir;

    private SqliteWorkflowExecutionRepo repo;

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("workflow-execution-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workflow_execution ("
                + "id TEXT PRIMARY KEY,"
                + "workflow_id TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "inputs_json TEXT,"
                + "started_at INTEGER NOT NULL,"
                + "finished_at INTEGER,"
                + "error_message TEXT,"
                + "created_by TEXT)");
        jdbc.execute("CREATE TABLE workflow_step_execution ("
                + "id TEXT PRIMARY KEY,"
                + "execution_id TEXT NOT NULL,"
                + "step_index INTEGER NOT NULL,"
                + "step_name TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "prompt TEXT NOT NULL,"
                + "output TEXT,"
                + "error_message TEXT,"
                + "started_at INTEGER NOT NULL,"
                + "finished_at INTEGER)");
        repo = new SqliteWorkflowExecutionRepo(jdbc);
    }

    @Test
    void saveExecution_then_findById_should_round_trip_fields() {
        // Given
        WorkflowExecution execution = buildExecution("exec-1", "wf-1");

        // When
        repo.save(execution);

        // Then
        WorkflowExecution loaded = repo.findById("exec-1");
        assertNotNull(loaded);
        assertEquals("wf-1", loaded.getWorkflowId());
        assertEquals(WorkflowStatus.RUNNING, loaded.getStatus());
        assertEquals("{\"branch\":\"main\"}", loaded.getInputsJson());
        assertNull(loaded.getFinishedAt());
    }

    @Test
    void updateExecution_should_persist_terminal_status_and_error() {
        // Given
        WorkflowExecution execution = buildExecution("exec-1", "wf-1");
        repo.save(execution);

        // When
        execution.markFailed("step failed");
        repo.update(execution);

        // Then
        WorkflowExecution loaded = repo.findById("exec-1");
        assertEquals(WorkflowStatus.FAILED, loaded.getStatus());
        assertEquals("step failed", loaded.getErrorMessage());
        assertNotNull(loaded.getFinishedAt());
    }

    @Test
    void saveAndUpdateStep_should_round_trip_step_execution() {
        // Given
        WorkflowStepExecution step = new WorkflowStepExecution(
                "step-1", "exec-1", 0, "review", WorkflowStatus.RUNNING,
                "prompt", null, null, Instant.parse("2026-06-12T08:00:00Z"), null);

        // When
        repo.saveStep(step);
        step.markSucceeded("output");
        repo.updateStep(step);

        // Then
        List<WorkflowStepExecution> steps = repo.findStepsByExecutionId("exec-1");
        assertEquals(1, steps.size());
        assertEquals(WorkflowStatus.SUCCEEDED, steps.get(0).getStatus());
        assertEquals("output", steps.get(0).getOutput());
        assertNotNull(steps.get(0).getFinishedAt());
    }

    @Test
    void findExecutions_should_filter_by_workflow_and_order_by_started_desc() {
        // Given
        repo.save(buildExecution("exec-old", "wf-1", Instant.parse("2026-06-12T08:00:00Z")));
        repo.save(buildExecution("exec-new", "wf-1", Instant.parse("2026-06-12T09:00:00Z")));
        repo.save(buildExecution("exec-other", "wf-2", Instant.parse("2026-06-12T10:00:00Z")));

        // When
        List<WorkflowExecution> rows = repo.findByWorkflowId("wf-1", 0, 10);

        // Then
        assertEquals(2, rows.size());
        assertEquals("exec-new", rows.get(0).getId());
        assertEquals("exec-old", rows.get(1).getId());
        assertEquals(2L, repo.countByWorkflowId("wf-1"));
    }

    private WorkflowExecution buildExecution(String id, String workflowId) {
        return buildExecution(id, workflowId, Instant.parse("2026-06-12T08:00:00Z"));
    }

    private WorkflowExecution buildExecution(String id, String workflowId, Instant startedAt) {
        return new WorkflowExecution(id, workflowId, WorkflowStatus.RUNNING,
                "{\"branch\":\"main\"}", startedAt, null, null, "u1");
    }
}
