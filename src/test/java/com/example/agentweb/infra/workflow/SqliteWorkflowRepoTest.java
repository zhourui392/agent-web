package com.example.agentweb.infra.workflow;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workflow.Workflow;
import com.example.agentweb.domain.workflow.WorkflowStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
class SqliteWorkflowRepoTest {

    @TempDir
    Path tempDir;

    private SqliteWorkflowRepo repo;

    @BeforeEach
    void setUp() {
        File dbFile = tempDir.resolve("workflow-test.db").toFile();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workflow_definition ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "description TEXT,"
                + "agent_type TEXT NOT NULL,"
                + "working_dir TEXT NOT NULL,"
                + "steps_json TEXT NOT NULL,"
                + "enabled INTEGER NOT NULL DEFAULT 1,"
                + "created_by TEXT,"
                + "created_at INTEGER NOT NULL,"
                + "updated_at INTEGER NOT NULL)");
        repo = new SqliteWorkflowRepo(jdbc);
    }

    @Test
    void save_then_findById_should_round_trip_steps_json() {
        // Given
        Workflow workflow = buildWorkflow("wf-1", "Review", true,
                new WorkflowStep("review", "Review {{inputs.branch}}", 1800L));

        // When
        repo.save(workflow);

        // Then
        Workflow loaded = repo.findById("wf-1");
        assertNotNull(loaded);
        assertEquals("Review", loaded.getName());
        assertEquals(AgentType.CODEX, loaded.getAgentType());
        assertEquals("E:/repo", loaded.getWorkingDir());
        assertEquals(1, loaded.getSteps().size());
        assertEquals("review", loaded.getSteps().get(0).getName());
        assertEquals("Review {{inputs.branch}}", loaded.getSteps().get(0).getPromptTemplate());
        assertEquals(1800L, loaded.getSteps().get(0).getTimeoutSeconds());
    }

    @Test
    void update_should_persist_mutable_fields() {
        // Given
        repo.save(buildWorkflow("wf-1", "Old", true,
                new WorkflowStep("old", "Old prompt", 0L)));
        Workflow updated = buildWorkflow("wf-1", "New", false,
                new WorkflowStep("new", "New prompt", 60L));

        // When
        repo.update(updated);

        // Then
        Workflow loaded = repo.findById("wf-1");
        assertEquals("New", loaded.getName());
        assertFalse(loaded.isEnabled());
        assertEquals("new", loaded.getSteps().get(0).getName());
        assertEquals("New prompt", loaded.getSteps().get(0).getPromptTemplate());
    }

    @Test
    void findAll_should_return_created_desc_order_and_delete_should_keep_no_definition() {
        // Given
        repo.save(buildWorkflow("wf-old", "Old", true,
                new WorkflowStep("review", "p1", 0L),
                Instant.parse("2026-06-12T08:00:00Z")));
        repo.save(buildWorkflow("wf-new", "New", true,
                new WorkflowStep("review", "p2", 0L),
                Instant.parse("2026-06-12T09:00:00Z")));

        // When
        List<Workflow> all = repo.findAll();
        repo.deleteById("wf-old");

        // Then
        assertEquals(Arrays.asList("wf-new", "wf-old"),
                Arrays.asList(all.get(0).getId(), all.get(1).getId()));
        assertNull(repo.findById("wf-old"));
    }

    private Workflow buildWorkflow(String id, String name, boolean enabled, WorkflowStep step) {
        return buildWorkflow(id, name, enabled, step, Instant.parse("2026-06-12T08:00:00Z"));
    }

    private Workflow buildWorkflow(String id, String name, boolean enabled, WorkflowStep step, Instant createdAt) {
        return new Workflow(id, name, "desc", AgentType.CODEX, "E:/repo",
                java.util.Collections.singletonList(step), enabled, "u1", createdAt, createdAt);
    }
}
