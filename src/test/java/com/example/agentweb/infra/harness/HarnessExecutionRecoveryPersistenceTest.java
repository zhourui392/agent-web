package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.DeploymentExecutionView;
import com.example.agentweb.app.harness.HarnessExecutionRecoveryService;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilitySnapshotReference;
import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionStatus;
import com.example.agentweb.domain.harness.DeploymentPermit;
import com.example.agentweb.domain.harness.DeploymentTemplateReference;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunStatus;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionStatus;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 使用真实 SQLite 验证重启恢复只关闭不确定动作，不创建或重放新的外部执行。
 *
 * @author alex
 * @since 2026-07-24
 */
class HarnessExecutionRecoveryPersistenceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteHarnessRunRepository runRepository;
    private SqliteRuntimeExecutionRepository runtimeRepository;
    private SqliteDeploymentExecutionRepository deploymentRepository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("recovery.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        runRepository = new SqliteHarnessRunRepository(jdbc);
        runtimeRepository = new SqliteRuntimeExecutionRepository(jdbc);
        deploymentRepository = new SqliteDeploymentExecutionRepository(jdbc);
    }

    @Test
    void shouldPersistRecoveryExactlyOnceWithoutCreatingReplacementExecutions() {
        HarnessRun run = activeRun();
        RuntimeExecution runtime = preparedRuntime(run);
        run.bindExecution(runtime.reference(), NOW.plusSeconds(3));
        runRepository.add(run);
        runtimeRepository.add(runtime);
        deploymentRepository.add(deployment("deploy-prepared", "deployment-key-1", false));
        deploymentRepository.add(deployment("deploy-running", "deployment-key-2", true));

        HarnessExecutionRecoveryService recovery = new HarnessExecutionRecoveryService(
                runtimeRepository, deploymentRepository, runRepository,
                Clock.fixed(NOW.plusSeconds(20), ZoneOffset.UTC));

        recovery.recoverUnfinishedExternalActions();
        recovery.recoverUnfinishedExternalActions();

        RuntimeExecution restoredRuntime = runtimeRepository.findById("runtime-1")
                .orElseThrow(AssertionError::new);
        assertEquals(RuntimeExecutionStatus.LOST, restoredRuntime.getStatus());
        assertEquals(1L, restoredRuntime.getLastEventSequence());
        assertEquals(HarnessRunStatus.FAILED, runRepository.findById("run-1")
                .orElseThrow(AssertionError::new).getStatus());
        assertEquals(0, runtimeRepository.findUnfinished().size());
        assertEquals(0, deploymentRepository.findUnfinished().size());

        List<DeploymentExecutionView> deployments =
                new SqliteDeploymentExecutionQueryService(jdbc).listByRun("run-1");
        assertEquals(2, deployments.size());
        assertEquals(DeploymentExecutionStatus.RECONCILIATION_REQUIRED.name(),
                deployments.get(0).getStatus());
        assertEquals(DeploymentExecutionStatus.RECONCILIATION_REQUIRED.name(),
                deployments.get(1).getStatus());
        assertEquals(1, count("harness_runtime_execution"));
        assertEquals(2, count("harness_deployment_execution"));
        assertEquals(1, count("harness_runtime_event"));
    }

    private HarnessRun activeRun() {
        HarnessRun run = HarnessRun.create("run-1", "M4 recovery", tempDir.toString(),
                "CODEX", "local", "harness@1", "admin", "create",
                StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));
        return run;
    }

    private RuntimeExecution preparedRuntime(HarnessRun run) {
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.ANALYSIS,
                new CapabilitySnapshotReference("run-1", HarnessStage.ANALYSIS, 1,
                        hash('a'), hash('b'), Collections.<String>emptySet()));
        return RuntimeExecution.prepare("runtime-1", "runtime-key", permit,
                AgentRuntime.CODEX, NOW.plusSeconds(2));
    }

    private DeploymentExecution deployment(String executionId, String idempotencyKey,
                                             boolean running) {
        WorkspaceBaseline baseline = baseline();
        DeploymentExecution execution = DeploymentExecution.prepare(executionId, idempotencyKey,
                new DeploymentPermit("run-1", 1, hash('c'), baseline),
                new DeploymentTemplateReference("local-default", "1", hash('d'), true),
                NOW.plusSeconds(4));
        if (running) {
            execution.begin(baseline, NOW.plusSeconds(5));
        }
        return execution;
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture(tempDir.toString(), "feat/harness-m4",
                "0123456789012345678901234567890123456789", true, hash('e'), NOW);
    }

    private int count(String table) {
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
