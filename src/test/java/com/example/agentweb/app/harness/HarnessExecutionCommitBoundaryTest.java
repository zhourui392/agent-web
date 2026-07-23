package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.AgentRuntimeGateway;
import com.example.agentweb.app.harness.port.RuntimeEventSink;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.HarnessHashing;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptPart;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpSelection;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.PromptPackManifest;
import com.example.agentweb.domain.harness.PromptPackResource;
import com.example.agentweb.domain.harness.PromptPartType;
import com.example.agentweb.domain.harness.PromptResourceRole;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import com.example.agentweb.infra.SqliteInitializer;
import com.example.agentweb.infra.harness.SqliteCapabilitySnapshotRepository;
import com.example.agentweb.infra.harness.SqliteHarnessRunRepository;
import com.example.agentweb.infra.harness.SqliteRuntimeExecutionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 SQLite + Spring 事务证明 Runtime Gateway 只能观察到已提交的准备状态。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = HarnessExecutionCommitBoundaryTest.TestConfig.class)
class HarnessExecutionCommitBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-07-23T15:00:00Z");

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private HarnessRunRepository runRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private CapabilitySnapshotRepository snapshotRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private HarnessExecutionLauncher launcher;
    @org.springframework.beans.factory.annotation.Autowired
    private CommitProbeGateway gateway;

    @BeforeEach
    void setUp() throws Exception {
        new SqliteInitializer(jdbc).init();
        HarnessRun run = HarnessRun.create("run-1", "M3", TestConfig.ROOT.toString(),
                "CODEX", "test", "harness@1.0.0", "admin", "create-1",
                StageContract.mvpDefaults(), NOW);
        run.startStage(HarnessStage.ANALYSIS, "start-1", NOW.plusSeconds(1));
        runRepository.add(run);
        snapshotRepository.saveIfAbsent(snapshot());
    }

    @Test
    void gatewayShouldRunOutsideTransactionAndObserveCommittedSnapshotBindingAndStartingExecution() {
        HarnessExecutionResult result = launcher.start(new StartHarnessExecutionCommand(
                "run-1", HarnessStage.ANALYSIS, "launch-1"));

        assertEquals("exec-1", result.getExecutionId());
        assertTrue(gateway.called);
        assertFalse(gateway.transactionActive);
        assertEquals("STARTING", gateway.executionStatus);
        assertEquals("exec-1", gateway.boundExecutionId);
        assertEquals(1, gateway.snapshotCount);

        HarnessMutationResult cancelled = launcher.cancel("run-1", "stop");

        assertEquals("CANCELLING", cancelled.getStatus());
        assertTrue(gateway.cancelCalled);
        assertFalse(gateway.cancelTransactionActive);
        assertEquals("CANCELLING", gateway.cancelRunStatus);
        assertEquals("CANCEL_REQUESTED", gateway.cancelExecutionStatus);
    }

    private CapabilitySnapshot snapshot() {
        Map<PromptResourceRole, String> paths = new EnumMap<PromptResourceRole, String>(
                PromptResourceRole.class);
        List<PromptPackResource> resources = new ArrayList<PromptPackResource>();
        for (PromptResourceRole role : PromptResourceRole.values()) {
            String path = role.name().toLowerCase() + ".md";
            String content = role.name().toLowerCase();
            paths.put(role, path);
            resources.add(new PromptPackResource(role, path, content, HarnessHashing.sha256(content)));
        }
        PromptPack pack = new PromptPack(new PromptPackManifest(
                "analysis", "1.0.0", HarnessStage.ANALYSIS, paths), resources,
                HarnessHashing.sha256("pack"));
        String prompt = "committed execution prompt";
        HarnessPromptAssembly assembly = new HarnessPromptAssembly(Collections.singletonList(
                HarnessPromptPart.from(PromptPartType.CURRENT_INPUT, "input", prompt)),
                prompt, HarnessHashing.sha256(prompt));
        return CapabilitySnapshot.create("run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX,
                "test", "harness-capability-policy@2.0.0", pack,
                new SkillSelection(Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()), McpSelection.none(),
                new RuntimeEnforcementProfile("codex-runtime-enforcement@1",
                        "codex-harness-adapter@1", "codex-cli 0.145.0",
                        "m0-2026-07-22", "read-only",
                        true, true, true, true, true, true),
                WorkspaceRuntimeInventory.empty(), assembly, NOW.plusSeconds(2));
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {

        private static final Path ROOT = tempRoot();

        @Bean
        DataSource dataSource() {
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            SQLiteDataSource dataSource = new SQLiteDataSource(config);
            dataSource.setUrl("jdbc:sqlite:" + ROOT.resolve("commit-boundary.db"));
            return dataSource;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        HarnessRunRepository runRepository(JdbcTemplate jdbc) {
            return new SqliteHarnessRunRepository(jdbc);
        }

        @Bean
        CapabilitySnapshotRepository snapshotRepository(JdbcTemplate jdbc) {
            return new SqliteCapabilitySnapshotRepository(jdbc);
        }

        @Bean
        RuntimeExecutionRepository executionRepository(JdbcTemplate jdbc) {
            return new SqliteRuntimeExecutionRepository(jdbc);
        }

        @Bean
        CurrentUserProvider currentUserProvider() {
            UserContext context = () -> Optional.of(new LoginUser("admin", "Admin", null));
            return new CurrentUserProvider(context);
        }

        @Bean
        HarnessIdGenerator harnessIdGenerator() {
            return () -> "exec-1";
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC);
        }

        @Bean
        HarnessExecutionPreparer preparer(HarnessRunRepository runRepository,
                                          CapabilitySnapshotRepository snapshotRepository,
                                          RuntimeExecutionRepository executionRepository,
                                          CurrentUserProvider currentUserProvider,
                                          HarnessIdGenerator idGenerator, Clock clock) {
            return new HarnessExecutionPreparer(runRepository, snapshotRepository,
                    executionRepository, currentUserProvider, idGenerator, clock);
        }

        @Bean
        HarnessRuntimeEventService eventService(RuntimeExecutionRepository executionRepository,
                                                HarnessRunRepository runRepository, Clock clock) {
            return new HarnessRuntimeEventService(executionRepository, runRepository, clock);
        }

        @Bean
        CommitProbeGateway gateway(JdbcTemplate jdbc) {
            return new CommitProbeGateway(jdbc);
        }

        @Bean
        HarnessExecutionLauncher launcher(HarnessExecutionPreparer preparer,
                                          HarnessRuntimeEventRecorder eventService,
                                          CommitProbeGateway gateway) {
            return new HarnessExecutionLauncher(preparer, eventService, gateway);
        }

        private static Path tempRoot() {
            try {
                return Files.createTempDirectory("harness-commit-boundary-");
            } catch (Exception ex) {
                throw new IllegalStateException("could not create transaction test root", ex);
            }
        }
    }

    static final class CommitProbeGateway implements AgentRuntimeGateway {

        private final JdbcTemplate jdbc;
        private boolean called;
        private boolean transactionActive;
        private String executionStatus;
        private String boundExecutionId;
        private int snapshotCount;
        private boolean cancelCalled;
        private boolean cancelTransactionActive;
        private String cancelRunStatus;
        private String cancelExecutionStatus;

        private CommitProbeGateway(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public void start(AgentExecutionSpec spec, RuntimeEventSink eventSink) {
            called = true;
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            executionStatus = jdbc.queryForObject("SELECT status FROM harness_runtime_execution "
                    + "WHERE execution_id=?", String.class, spec.getExecutionId());
            boundExecutionId = jdbc.queryForObject("SELECT execution_id FROM harness_stage_attempt "
                    + "WHERE run_id=? AND stage=? AND attempt_number=?", String.class,
                    spec.getRunId(), spec.getStage().name(), spec.getAttemptNumber());
            snapshotCount = jdbc.queryForObject("SELECT COUNT(*) FROM harness_capability_snapshot "
                    + "WHERE run_id=? AND stage=? AND attempt_number=?", Integer.class,
                    spec.getRunId(), spec.getStage().name(), spec.getAttemptNumber()).intValue();
        }

        @Override
        public void cancel(String executionId) {
            cancelCalled = true;
            cancelTransactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            cancelRunStatus = jdbc.queryForObject("SELECT status FROM harness_run WHERE id='run-1'",
                    String.class);
            cancelExecutionStatus = jdbc.queryForObject(
                    "SELECT status FROM harness_runtime_execution WHERE execution_id=?",
                    String.class, executionId);
        }
    }
}
