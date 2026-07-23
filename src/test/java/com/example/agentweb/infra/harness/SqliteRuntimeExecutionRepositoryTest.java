package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeCleanupStatus;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.RuntimeExecutionSignalType;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RuntimeExecution 与幂等 RuntimeEvent 的真实 SQLite 测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteRuntimeExecutionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T14:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteRuntimeExecutionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("runtime.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        HarnessRun run = HarnessRun.create("run-1", "M3", tempDir.toString(), "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start-1", NOW.plusSeconds(1));
        new SqliteHarnessRunRepository(jdbc).add(run);
        repository = new SqliteRuntimeExecutionRepository(jdbc);
    }

    @Test
    void shouldPersistLifecycleAndKeepRuntimeEventsIdempotent() {
        RuntimeExecution execution = execution("exec-1", "launch-1");
        repository.add(execution);
        execution.markStarting(NOW.plusSeconds(2));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-test", "pid-1",
                NOW.plusSeconds(3)));
        repository.update(execution);
        RuntimeExecutionEvent event = new RuntimeExecutionEvent("exec-1", 1L,
                RuntimeExecutionSignalType.STARTED, "runtime started", null, NOW.plusSeconds(3));
        repository.appendEvent(event);
        repository.appendEvent(event);

        RuntimeExecution restored = new SqliteRuntimeExecutionRepository(jdbc)
                .findById("exec-1").orElseThrow(AssertionError::new);

        assertEquals(RuntimeExecutionStatus.RUNNING, restored.getStatus());
        assertEquals(RuntimeCleanupStatus.PENDING, restored.getCleanupStatus());
        assertEquals("pid-1", restored.getRuntimeHandle());
        assertEquals("exec-1", repository.findByAttempt(
                "run-1", HarnessStage.ANALYSIS, 1).orElseThrow(AssertionError::new).getExecutionId());
        assertEquals("exec-1", repository.findByIdempotencyKey(
                "run-1", "launch-1").orElseThrow(AssertionError::new).getExecutionId());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_runtime_event", Integer.class).intValue());
    }

    @Test
    void oneAttemptShouldNotAcceptSecondRuntimeExecution() {
        repository.add(execution("exec-1", "launch-1"));

        assertThrows(IllegalStateException.class,
                () -> repository.add(execution("exec-2", "launch-2")));
    }

    private RuntimeExecution execution(String executionId, String idempotencyKey) {
        ExecutionPermit permit = new ExecutionPermit("run-1", HarnessStage.ANALYSIS, 1,
                hash('a'), hash('b'), Collections.singleton("reader"));
        return RuntimeExecution.prepare(executionId, idempotencyKey, permit,
                AgentRuntime.CODEX, NOW.plusSeconds(1));
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
