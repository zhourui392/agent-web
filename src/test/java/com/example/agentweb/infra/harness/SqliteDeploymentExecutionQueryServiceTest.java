package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.DeploymentExecutionView;
import com.example.agentweb.domain.harness.DeploymentExecution;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 部署执行 CQRS 投影的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-07-24
 */
class SqliteDeploymentExecutionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @TempDir
    Path tempDir;

    private SqliteDeploymentExecutionRepository repository;
    private SqliteDeploymentExecutionQueryService queryService;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("deployment-query.db").toAbsolutePath());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new SqliteInitializer(jdbc).init();
        new SqliteHarnessRunRepository(jdbc).add(HarnessRun.create(
                "run-1", "M4", tempDir.toString(), "CODEX", "local", "harness@1",
                "admin", "create", StageContract.mvpDefaults(), NOW));
        repository = new SqliteDeploymentExecutionRepository(jdbc);
        queryService = new SqliteDeploymentExecutionQueryService(jdbc);
    }

    @Test
    void shouldProjectRunScopedExecutionsInPreparationOrderAndReturnMissing() {
        DeploymentExecution first = execution("deploy-1", "key-1", NOW.plusSeconds(1));
        DeploymentExecution second = execution("deploy-2", "key-2", NOW.plusSeconds(2));
        repository.add(second);
        repository.add(first);
        second.begin(baseline(), NOW.plusSeconds(3));
        second.fail("health check failed", NOW.plusSeconds(4));
        repository.update(second);

        List<DeploymentExecutionView> views = queryService.listByRun("run-1");

        assertEquals(2, views.size());
        assertEquals("deploy-1", views.get(0).getExecutionId());
        assertEquals("PREPARED", views.get(0).getStatus());
        assertEquals("deploy-2", views.get(1).getExecutionId());
        assertEquals("FAILED", views.get(1).getStatus());
        assertEquals("health check failed", views.get(1).getFailureReason());
        assertEquals("local-default", views.get(1).getTemplateId());
        assertEquals(hash('b'), views.get(1).getApprovedInputBaselineHash());
        assertEquals("deploy-2", queryService.find("run-1", "deploy-2")
                .orElseThrow(AssertionError::new).getExecutionId());
        assertFalse(queryService.find("run-1", "missing").isPresent());
        assertFalse(queryService.find("other-run", "deploy-2").isPresent());
    }

    private DeploymentExecution execution(String executionId, String key, Instant preparedAt) {
        return DeploymentExecution.prepare(executionId, key,
                new DeploymentPermit("run-1", 1, hash('b'), baseline()),
                new DeploymentTemplateReference("local-default", "1", hash('c'), true),
                preparedAt);
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture(tempDir.toString(), "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('a'), NOW);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
