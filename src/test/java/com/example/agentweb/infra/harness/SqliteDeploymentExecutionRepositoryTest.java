package com.example.agentweb.infra.harness;

import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionStatus;
import com.example.agentweb.domain.harness.DeploymentPermit;
import com.example.agentweb.domain.harness.DeploymentTemplateReference;
import com.example.agentweb.domain.harness.HarnessRun;
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
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 部署执行聚合与重启对账查询的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class SqliteDeploymentExecutionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteDeploymentExecutionRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("deployment.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        HarnessRun run = HarnessRun.create("run-1", "M4", tempDir.toString(), "CODEX", "local",
                "harness@1", "admin", "create", StageContract.mvpDefaults(), NOW);
        new SqliteHarnessRunRepository(jdbc).add(run);
        repository = new SqliteDeploymentExecutionRepository(jdbc);
    }

    @Test
    void shouldRestorePreparedAndRunningExecutionsForManualReconciliation() {
        DeploymentExecution prepared = execution("deploy-1", "key-1");
        DeploymentExecution running = execution("deploy-2", "key-2");
        repository.add(prepared);
        repository.add(running);
        running.begin(baseline(), NOW.plusSeconds(2));
        repository.update(running);

        assertEquals(2, repository.findUnfinished().size());
        DeploymentExecution restored = repository.findByIdempotencyKey("run-1", "key-2")
                .orElseThrow(AssertionError::new);
        assertEquals(DeploymentExecutionStatus.RUNNING, restored.getStatus());
        assertEquals(hash('a'), restored.getWorkspaceBaseline().getDiffHash());
        assertEquals("local-default", restored.getTemplate().getTemplateId());

        restored.requireReconciliation("application restarted", NOW.plusSeconds(3));
        repository.update(restored);
        assertEquals(DeploymentExecutionStatus.RECONCILIATION_REQUIRED,
                repository.findById("deploy-2").orElseThrow(AssertionError::new).getStatus());
    }

    @Test
    void idempotencyKeyShouldBeUniqueWithinRun() {
        repository.add(execution("deploy-1", "key-1"));

        assertThrows(IllegalStateException.class,
                () -> repository.add(execution("deploy-2", "key-1")));
    }

    private DeploymentExecution execution(String id, String key) {
        return DeploymentExecution.prepare(id, key,
                new DeploymentPermit("run-1", 1, hash('b'), baseline()),
                new DeploymentTemplateReference("local-default", "1", hash('c'), true),
                NOW.plusSeconds(1));
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture(tempDir.toString(), "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('a'), NOW);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
