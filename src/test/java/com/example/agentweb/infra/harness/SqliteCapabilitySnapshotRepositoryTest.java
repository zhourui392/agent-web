package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.CapabilitySnapshotView;
import com.example.agentweb.app.harness.RuntimeExecutionView;
import com.example.agentweb.domain.harness.AgentRuntime;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.CapabilitySelectionRequest;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotReference;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessPromptAssembler;
import com.example.agentweb.domain.harness.HarnessPromptAssembly;
import com.example.agentweb.domain.harness.HarnessPromptAssemblyRequest;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.McpCapability;
import com.example.agentweb.domain.harness.McpCapabilityType;
import com.example.agentweb.domain.harness.McpSelection;
import com.example.agentweb.domain.harness.McpSecretReference;
import com.example.agentweb.domain.harness.McpServerDefinition;
import com.example.agentweb.domain.harness.PromptPack;
import com.example.agentweb.domain.harness.RuntimeEnforcementProfile;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.SelectedMcpServer;
import com.example.agentweb.domain.harness.SkillSelection;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.domain.harness.StageCapabilityPolicy;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.harness.WorkspaceBoundaryKind;
import com.example.agentweb.domain.harness.WorkspaceRepoSkill;
import com.example.agentweb.domain.harness.WorkspaceRuntimeInventory;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capability Snapshot 真实 SQLite 不可变持久化与 CQRS 预览测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteCapabilitySnapshotRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteCapabilitySnapshotRepository repository;
    private SqliteHarnessRunRepository harnessRunRepository;
    private FileSystemPromptPackCatalog promptCatalog;
    private FileSystemSkillCatalog skillCatalog;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("snapshot.db").toAbsolutePath());
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        dataSource.setConfig(config);
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        repository = new SqliteCapabilitySnapshotRepository(jdbc);
        harnessRunRepository = new SqliteHarnessRunRepository(jdbc);
        promptCatalog = new FileSystemPromptPackCatalog(
                java.nio.file.Paths.get("src/main/resources/harness/prompt-packs"));
        skillCatalog = new FileSystemSkillCatalog(
                java.nio.file.Paths.get("src/main/resources/harness/skills"),
                com.example.agentweb.domain.harness.SkillTrustSource.PLATFORM);

        HarnessRun run = HarnessRun.create("run-1", "M2", tempDir.toString(), "CODEX", "test",
                "harness@1.0.0", "admin", "create-1", StageContract.mvpDefaults(),
                Instant.parse("2026-07-23T10:00:00Z"));
        run.startStage(HarnessStage.ANALYSIS, "start-1", Instant.parse("2026-07-23T10:01:00Z"));
        harnessRunRepository.add(run);
    }

    @Test
    void shouldRestoreCompleteSnapshotAfterRepositoryRestart() {
        CapabilitySnapshot snapshot = snapshot("current input", Instant.parse("2026-07-23T10:02:00Z"));

        repository.saveIfAbsent(snapshot);
        CapabilitySnapshot restored = new SqliteCapabilitySnapshotRepository(jdbc)
                .find("run-1", HarnessStage.ANALYSIS, 1).orElseThrow(AssertionError::new);

        assertEquals(snapshot.getSnapshotHash(), restored.getSnapshotHash());
        assertEquals(snapshot.getPromptHash(), restored.getPromptHash());
        assertEquals(snapshot.getFinalPrompt(), restored.getFinalPrompt());
        assertEquals("domain-modeling-audit", restored.getSelectedSkills().get(0).getId());
        assertFalse(restored.getPromptParts().isEmpty());
    }

    @Test
    void shouldKeepFirstSnapshotWhenSameAttemptIsResolvedAgain() {
        CapabilitySnapshot first = snapshot("first input", Instant.parse("2026-07-23T10:02:00Z"));
        CapabilitySnapshot changed = snapshot("changed input", Instant.parse("2026-07-23T10:03:00Z"));

        CapabilitySnapshot saved = repository.saveIfAbsent(first);
        CapabilitySnapshot existing = repository.saveIfAbsent(changed);

        assertEquals(first.getSnapshotHash(), saved.getSnapshotHash());
        assertEquals(first.getSnapshotHash(), existing.getSnapshotHash());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM harness_capability_snapshot",
                Integer.class).intValue());
    }

    @Test
    void queryServiceShouldProjectSnapshotWithoutReturningPartialAggregate() {
        CapabilitySnapshot snapshot = snapshot("current input", Instant.parse("2026-07-23T10:02:00Z"));
        repository.saveIfAbsent(snapshot);
        SqliteCapabilitySnapshotQueryService queryService = new SqliteCapabilitySnapshotQueryService(jdbc);

        CapabilitySnapshotView view = queryService.find("run-1", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);

        assertEquals(snapshot.getSnapshotHash(), view.getSnapshotHash());
        assertEquals("STAGE_DEFAULT", view.getSelectedSkills().get(0).getReason().name());
        assertTrue(queryService.find("missing", HarnessStage.ANALYSIS, 1).isEmpty());
    }

    @Test
    void shouldRestoreM31McpWorkspaceAndRuntimeEnforcementWithoutSecretValues() {
        CapabilitySnapshot snapshot = m31Snapshot(true, 10, 30,
                inventory("trusted", "trusted entry"), enforcement("0.145.0"));

        repository.saveIfAbsent(snapshot);
        CapabilitySnapshot restored = repository.find("run-1", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);

        assertEquals(CapabilitySnapshot.SCHEMA_M3_1, restored.getSchemaVersion());
        assertEquals("reader", restored.getSelectedMcpServers().get(0).getId());
        assertTrue(restored.getSelectedMcpServers().get(0).isRequired());
        assertEquals(Collections.singletonList("search"),
                restored.getSelectedMcpServers().get(0).getEnabledToolNames());
        assertEquals(Collections.singletonList("update"),
                restored.getSelectedMcpServers().get(0).getDisabledToolNames());
        assertEquals(10, restored.getSelectedMcpServers().get(0).getStartupTimeoutSeconds());
        assertEquals(30, restored.getSelectedMcpServers().get(0).getToolTimeoutSeconds());
        assertEquals("READER_TOKEN", restored.getSelectedMcpServers().get(0)
                .getSecretReferences().get(0).getReference());
        assertEquals("0.145.0", restored.getRuntimeEnforcementProfile().getRuntimeVersion());
        assertEquals(snapshot.getWorkspaceRuntimeInventory().getInventoryHash(),
                restored.getWorkspaceRuntimeInventory().getInventoryHash());
        assertFalse(restored.getFinalPrompt().contains("secret-value"));
    }

    @Test
    void m31HashShouldIncludeRequiredToolsTimeoutsWorkspaceAndRuntimeVersion() {
        WorkspaceRuntimeInventory empty = WorkspaceRuntimeInventory.empty();
        RuntimeEnforcementProfile enforcement = enforcement("0.145.0");
        CapabilitySnapshot required = m31Snapshot(true, 10, 30, empty, enforcement);
        CapabilitySnapshot optional = m31Snapshot(false, 10, 30, empty, enforcement);
        CapabilitySnapshot timeoutChanged = m31Snapshot(true, 11, 31, empty, enforcement);
        CapabilitySnapshot workspaceChanged = m31Snapshot(true, 10, 30,
                inventory("trusted", "trusted entry"), enforcement);
        CapabilitySnapshot runtimeChanged = m31Snapshot(true, 10, 30, empty,
                enforcement("0.146.0"));

        assertNotEquals(required.getSnapshotHash(), optional.getSnapshotHash());
        assertNotEquals(required.getSnapshotHash(), timeoutChanged.getSnapshotHash());
        assertNotEquals(required.getSnapshotHash(), workspaceChanged.getSnapshotHash());
        assertNotEquals(required.getSnapshotHash(), runtimeChanged.getSnapshotHash());
    }

    @Test
    void legacySnapshotShouldReadAsM2WithoutRecalculatingOriginalHash() {
        CapabilitySnapshot legacy = snapshot("legacy input", Instant.parse("2026-07-23T10:02:00Z"));

        repository.saveIfAbsent(legacy);
        CapabilitySnapshot restored = repository.find("run-1", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);

        assertEquals(CapabilitySnapshot.SCHEMA_M2, restored.getSchemaVersion());
        assertTrue(restored.getSelectedMcpServers().isEmpty());
        assertEquals(legacy.getSnapshotHash(), restored.getSnapshotHash());
        assertThrows(com.example.agentweb.domain.harness.IllegalHarnessTransitionException.class,
                () -> CapabilitySnapshotReference.from(restored));
    }

    @Test
    void snapshotShouldSurviveArtifactGateApprovalAndCancelRunUpdatesWithoutChangingHash() {
        CapabilitySnapshot saved = repository.saveIfAbsent(
                snapshot("current input", Instant.parse("2026-07-23T10:02:00Z")));
        HarnessRun run = harnessRunRepository.findById("run-1").orElseThrow(AssertionError::new);
        int second = 3;
        for (ArtifactType type : run.capabilityStageContract(HarnessStage.ANALYSIS)
                .getRequiredOutputArtifacts()) {
            run.registerArtifact(HarnessStage.ANALYSIS, "artifact-" + type.name(), type,
                    ArtifactContent.from(type.name().getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "admin",
                    Collections.<ArtifactReference>emptyList(),
                    Instant.parse("2026-07-23T10:00:00Z").plusSeconds(second++ * 60L));
        }
        harnessRunRepository.update(run);
        assertSnapshotHash(saved.getSnapshotHash());

        for (String gate : run.capabilityStageContract(HarnessStage.ANALYSIS).getDeterministicGates()) {
            run.recordGate(HarnessStage.ANALYSIS, "gate-" + gate, gate, true,
                    Collections.<String>emptyList(), null,
                    Instant.parse("2026-07-23T11:00:00Z"));
        }
        harnessRunRepository.update(run);
        assertSnapshotHash(saved.getSnapshotHash());

        run.submitForApproval(HarnessStage.ANALYSIS, Instant.parse("2026-07-23T11:01:00Z"));
        harnessRunRepository.update(run);
        run.approve(HarnessStage.ANALYSIS, "approval-1",
                run.currentArtifactBaselineHash(HarnessStage.ANALYSIS), "admin", "approved",
                Instant.parse("2026-07-23T11:02:00Z"));
        harnessRunRepository.update(run);
        assertSnapshotHash(saved.getSnapshotHash());

        run.cancel("admin", "stop", Instant.parse("2026-07-23T11:03:00Z"));
        harnessRunRepository.update(run);
        assertSnapshotHash(saved.getSnapshotHash());
    }

    @Test
    void m31SnapshotAndExecutionReferencesShouldSurviveCompleteRunLifecycleUpdates() {
        CapabilitySnapshot saved = repository.saveIfAbsent(m31Snapshot(true, 10, 30,
                WorkspaceRuntimeInventory.empty(), enforcement("0.145.0")));
        HarnessRun run = harnessRunRepository.findById("run-1").orElseThrow(AssertionError::new);
        ExecutionPermit permit = run.authorizeExecution(HarnessStage.ANALYSIS,
                CapabilitySnapshotReference.from(saved));
        harnessRunRepository.update(run);

        SqliteRuntimeExecutionRepository executionRepository =
                new SqliteRuntimeExecutionRepository(jdbc);
        RuntimeExecution execution = RuntimeExecution.prepare("exec-1", "launch-1", permit,
                AgentRuntime.CODEX, Instant.parse("2026-07-23T10:03:00Z"));
        executionRepository.add(execution);
        run.bindExecution(execution.reference(), Instant.parse("2026-07-23T10:04:00Z"));
        harnessRunRepository.update(run);

        execution.markStarting(Instant.parse("2026-07-23T10:05:00Z"));
        execution.apply(RuntimeExecutionSignal.started(1L, "codex-cli 0.145.0", "pid:123",
                Instant.parse("2026-07-23T10:06:00Z")));
        execution.apply(RuntimeExecutionSignal.succeeded(2L, 0, "evidence/exec-1.jsonl", true,
                Instant.parse("2026-07-23T10:07:00Z")));
        executionRepository.update(execution);
        run.recordExecutionSucceeded(execution.reference(),
                Instant.parse("2026-07-23T10:08:00Z"));
        harnessRunRepository.update(run);

        int minute = 9;
        for (ArtifactType type : run.capabilityStageContract(HarnessStage.ANALYSIS)
                .getRequiredOutputArtifacts()) {
            run.registerArtifact(HarnessStage.ANALYSIS, "artifact-" + type.name(), type,
                    ArtifactContent.from(type.name().getBytes(StandardCharsets.UTF_8)),
                    "text/markdown", ArtifactClassification.INTERNAL, "admin",
                    Collections.<ArtifactReference>emptyList(),
                    Instant.parse("2026-07-23T10:00:00Z").plusSeconds(minute++ * 60L));
        }
        harnessRunRepository.update(run);
        for (String gate : run.capabilityStageContract(HarnessStage.ANALYSIS)
                .getDeterministicGates()) {
            run.recordGate(HarnessStage.ANALYSIS, "gate-" + gate, gate, true,
                    Collections.<String>emptyList(), null,
                    Instant.parse("2026-07-23T11:00:00Z"));
        }
        harnessRunRepository.update(run);
        run.submitForApproval(HarnessStage.ANALYSIS, Instant.parse("2026-07-23T11:01:00Z"));
        harnessRunRepository.update(run);
        run.approve(HarnessStage.ANALYSIS, "approval-1",
                run.currentArtifactBaselineHash(HarnessStage.ANALYSIS), "admin", "approved",
                Instant.parse("2026-07-23T11:02:00Z"));
        harnessRunRepository.update(run);
        run.cancel("admin", "stop", Instant.parse("2026-07-23T11:03:00Z"));
        harnessRunRepository.update(run);

        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_capability_snapshot WHERE run_id='run-1' "
                        + "AND stage='ANALYSIS' AND attempt_number=1", Integer.class).intValue());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM harness_runtime_execution WHERE execution_id='exec-1'",
                Integer.class).intValue());
        assertAttemptReferences(saved.getSnapshotHash(), "exec-1");
        assertEquals(saved.getSnapshotHash(), jdbc.queryForObject(
                "SELECT snapshot_hash FROM harness_capability_snapshot WHERE run_id='run-1' "
                        + "AND stage='ANALYSIS' AND attempt_number=1", String.class));
        assertEquals(saved.getSnapshotHash(), jdbc.queryForObject(
                "SELECT snapshot_hash FROM harness_runtime_execution WHERE execution_id='exec-1'",
                String.class));
        assertEquals(saved.getSnapshotHash(), repository.find(
                "run-1", HarnessStage.ANALYSIS, 1).orElseThrow(AssertionError::new)
                .getSnapshotHash());
        assertEquals("exec-1", executionRepository.findById("exec-1")
                .orElseThrow(AssertionError::new).getExecutionId());
        RuntimeExecutionView executionView = new SqliteRuntimeExecutionQueryService(jdbc)
                .find("run-1", HarnessStage.ANALYSIS, 1).orElseThrow(AssertionError::new);
        assertEquals("SUCCEEDED", executionView.getStatus());
        assertEquals("reader", executionView.getSelectedMcpServers().get(0).getId());
    }

    private void assertSnapshotHash(String expectedHash) {
        CapabilitySnapshot restored = repository.find("run-1", HarnessStage.ANALYSIS, 1)
                .orElseThrow(AssertionError::new);
        assertEquals(expectedHash, restored.getSnapshotHash());
    }

    private void assertAttemptReferences(String expectedSnapshotHash, String expectedExecutionId) {
        assertEquals(expectedSnapshotHash, jdbc.queryForObject(
                "SELECT snapshot_hash FROM harness_stage_attempt WHERE run_id='run-1' "
                        + "AND stage='ANALYSIS' AND attempt_number=1", String.class));
        assertEquals(expectedExecutionId, jdbc.queryForObject(
                "SELECT execution_id FROM harness_stage_attempt WHERE run_id='run-1' "
                        + "AND stage='ANALYSIS' AND attempt_number=1", String.class));
        HarnessRun restored = harnessRunRepository.findById("run-1")
                .orElseThrow(AssertionError::new);
        assertEquals(expectedSnapshotHash, restored.stage(HarnessStage.ANALYSIS)
                .currentAttempt().getSnapshotHash());
        assertEquals(expectedExecutionId, restored.stage(HarnessStage.ANALYSIS)
                .currentAttempt().getExecutionId());
    }

    private CapabilitySnapshot snapshot(String currentInput, Instant createdAt) {
        PromptPack pack = promptCatalog.resolve(HarnessStage.ANALYSIS);
        SkillSelection selection = new SkillSelectionPolicy().select(
                new CapabilitySelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                        StageCapabilityPolicy.defaultsFor(HarnessStage.ANALYSIS),
                        Collections.<String>emptySet(), Collections.singleton("java"),
                        Collections.<String>emptySet(), CapabilityGrant.none()),
                skillCatalog.discover());
        HarnessPromptAssembly assembly = new HarnessPromptAssembler().assemble(
                new HarnessPromptAssemblyRequest("platform safety", "test guardrail", "stage contract",
                        pack, selection, "approved upstream", currentInput));
        return CapabilitySnapshot.create("run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX,
                "test", "harness-capability-policy@1.0.0", pack, selection, assembly, createdAt);
    }

    private CapabilitySnapshot m31Snapshot(boolean required, int startupTimeout,
                                           int toolTimeout,
                                           WorkspaceRuntimeInventory inventory,
                                           RuntimeEnforcementProfile enforcement) {
        PromptPack pack = promptCatalog.resolve(HarnessStage.ANALYSIS);
        SkillSelection skillSelection = new SkillSelectionPolicy().select(
                new CapabilitySelectionRequest(HarnessStage.ANALYSIS, AgentRuntime.CODEX,
                        StageCapabilityPolicy.defaultsFor(HarnessStage.ANALYSIS),
                        Collections.<String>emptySet(), Collections.singleton("java"),
                        Collections.<String>emptySet(), CapabilityGrant.none()),
                skillCatalog.discover());
        HarnessPromptAssembly assembly = new HarnessPromptAssembler().assemble(
                new HarnessPromptAssemblyRequest("platform safety", "test guardrail", "stage contract",
                        pack, skillSelection, "approved upstream", "current input"));
        McpServerDefinition definition = new McpServerDefinition("reader", "1.0.0", "reader",
                Collections.singleton(HarnessStage.ANALYSIS), Collections.singleton(AgentRuntime.CODEX),
                Arrays.asList("fake-mcp", "--stdio"), Arrays.asList(
                new McpCapability("search", McpCapabilityType.TOOL,
                        com.example.agentweb.domain.harness.CapabilityAccess.READ),
                new McpCapability("update", McpCapabilityType.TOOL,
                        com.example.agentweb.domain.harness.CapabilityAccess.WRITE)),
                Collections.singletonList(new McpSecretReference("READER_API_KEY", "READER_TOKEN")),
                startupTimeout, toolTimeout,
                com.example.agentweb.domain.harness.HarnessHashing.sha256("reader"));
        McpSelection mcpSelection = new McpSelection(
                Collections.singletonList(new SelectedMcpServer(definition, required)),
                Collections.emptyList());
        return CapabilitySnapshot.create("run-1", HarnessStage.ANALYSIS, 1, AgentRuntime.CODEX,
                "test", "harness-capability-policy@2.0.0", pack, skillSelection,
                mcpSelection, enforcement, inventory, assembly,
                Instant.parse("2026-07-23T10:02:00Z"));
    }

    private RuntimeEnforcementProfile enforcement(String runtimeVersion) {
        return new RuntimeEnforcementProfile("codex-runtime-enforcement@2",
                "codex-harness-adapter@2", runtimeVersion, "codex-m0@2026-07-22",
                "read-only", true, true, true, true, true, true);
    }

    private WorkspaceRuntimeInventory inventory(String id, String content) {
        return new WorkspaceRuntimeInventory(WorkspaceBoundaryKind.GIT_ROOT, true,
                Collections.singletonList(new WorkspaceRepoSkill(id,
                        ".agents/skills/" + id + "/SKILL.md",
                        com.example.agentweb.domain.harness.HarnessHashing.sha256(content))));
    }
}
