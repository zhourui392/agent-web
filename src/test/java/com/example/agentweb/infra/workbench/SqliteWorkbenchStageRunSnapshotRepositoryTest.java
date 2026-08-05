package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.ResolvedCommandBinding;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentContentState;
import com.example.agentweb.domain.workbench.context.WorkbenchContextDocumentSnapshot;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.StageCommandReference;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态 Stage Run Snapshot 的真实 SQLite 双读边界测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageRunSnapshotRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @TempDir
    Path temporaryDirectory;

    private JdbcTemplate jdbcTemplate;
    private SqliteWorkbenchStageRunSnapshotRepository snapshotRepository;
    private SqliteWorkbenchStageRunPromptPayloadRepository promptRepository;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private Workbench workbench;
    private WorkbenchStageSnapshot stageSnapshot;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate = WorkbenchPersistenceFixtures.initializedJdbc(
                temporaryDirectory.resolve("stage-run-snapshot.db"));
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbcTemplate, temporaryDirectory, "stage-run-workspace");
        stageSnapshot = stageSnapshot();
        workbench = Workbench.create(
                com.example.agentweb.domain.workbench.WorkbenchId.of(
                        "workbench-stage-run"),
                WorkbenchPersistenceFixtures.OWNER,
                "Dynamic Workbench", "Implement dynamic Run",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        "stage-design", stageSnapshot)), NOW);
        new SqliteWorkbenchRepository(jdbcTemplate).add(workbench);
        snapshotRepository =
                new SqliteWorkbenchStageRunSnapshotRepository(jdbcTemplate);
        promptRepository =
                new SqliteWorkbenchStageRunPromptPayloadRepository(
                        jdbcTemplate);
    }

    @Test
    void should_RoundTripStageCommandContextCapabilityPromptAndRuntimeFacts() {
        // Given
        WorkbenchStageRunSnapshot source = snapshot("run-stage-1");
        WorkbenchRunPromptPayload prompt = promptPayload("run-stage-1");

        // When
        snapshotRepository.add(source);
        promptRepository.add(prompt);
        WorkbenchStageRunSnapshot restored = snapshotRepository
                .findByRunId("run-stage-1")
                .orElseThrow(AssertionError::new);
        WorkbenchRunPromptPayload restoredPrompt = promptRepository
                .findByRunId("run-stage-1")
                .orElseThrow(AssertionError::new);

        // Then
        assertEquals(source.getStageInstanceIdentifier(),
                restored.getStageInstanceIdentifier());
        assertEquals(source.getStageSnapshotHash(),
                restored.getStageSnapshotHash());
        assertEquals(source.getCommandBinding().getExpandedPromptHash(),
                restored.getCommandBinding().getExpandedPromptHash());
        assertEquals(source.getCapabilityBinding().getBindingHash(),
                restored.getCapabilityBinding().getBindingHash());
        assertEquals(source.getContextHash(), restored.getContextHash());
        assertEquals(source.getContextDocumentReferences(),
                restored.getContextDocumentReferences());
        assertEquals(source.getPromptParts().size(),
                restored.getPromptParts().size());
        assertEquals(source.getPromptParts().get(0).getContentHash(),
                restored.getPromptParts().get(0).getContentHash());
        assertEquals(source.getRuntimeEnforcement().getRunMode(),
                restored.getRuntimeEnforcement().getRunMode());
        assertEquals(prompt.getPromptHash(), restoredPrompt.getPromptHash());
        assertEquals(prompt.getFinalPrompt(), restoredPrompt.getFinalPrompt());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workbench_stage_run_prompt_payload",
                Integer.class));
        WorkbenchStageUploadedAttachmentBinding restoredBinding = restored
                .getVerifiedUploadedAttachments().get(0).getBinding();
        assertEquals(WorkbenchPersistenceFixtures.OWNER,
                restoredBinding.getOwner());
        assertEquals(workbench.getId(), restoredBinding.getWorkbenchId());
        assertEquals("stage-design",
                restoredBinding.getStageInstanceIdentifier());
        assertEquals("stage-session-1",
                restoredBinding.getConversationId());
        assertEquals(2, restoredBinding.getConversationGeneration());
        String attachmentsJson = jdbcTemplate.queryForObject(
                "SELECT attachments_json "
                        + "FROM workbench_stage_run_snapshot WHERE run_id=?",
                String.class, "run-stage-1");
        assertTrue(attachmentsJson.contains(
                "\"stageInstanceIdentifier\":\"stage-design\""));
        assertFalse(attachmentsJson.contains("\"phase\""));
        assertEquals("run-stage-1", snapshotRepository
                .findByWorkbenchStageAndIdempotencyKey(
                        workbench.getId(), "stage-design", "submit-stage-1")
                .orElseThrow(AssertionError::new).getRunId());
        assertEquals("run-stage-1", snapshotRepository.findReplayCandidate(
                        WorkbenchPersistenceFixtures.OWNER,
                        workbench.getId(), "stage-design", "submit-stage-1")
                .orElseThrow(AssertionError::new).getRunId());
        assertFalse(snapshotRepository.findReplayCandidate(
                WorkbenchPersistenceFixtures.OWNER_2,
                workbench.getId(), "stage-design", "submit-stage-1")
                .isPresent());
    }

    @Test
    void should_RejectDuplicateStageIdempotencyKeyAndCorruptedCommandPayload() {
        // Given
        WorkbenchStageRunSnapshot source = snapshot("run-stage-1");
        snapshotRepository.add(source);

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.add(snapshot("run-stage-2")));

        jdbcTemplate.update(
                "UPDATE workbench_stage_run_snapshot "
                        + "SET command_binding_json=? WHERE run_id=?",
                "{\"identifier\":\"tampered\"}", "run-stage-1");
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByRunId("run-stage-1"));
    }

    private WorkbenchStageRunSnapshot snapshot(String runIdentifier) {
        return WorkbenchStageRunSnapshot.create(
                runIdentifier, workbench.getId(), "stage-design", stageSnapshot,
                "submit-stage-1", WorkbenchPersistenceFixtures.HASH_A,
                RunMode.DISCUSS_READ_ONLY, workspace.scope(),
                workspace.snapshot().reference(),
                WorkbenchPersistenceFixtures.capabilityBinding(),
                commandBinding(), 4L, WorkbenchPersistenceFixtures.HASH_B,
                Collections.singletonList(new WorkbenchContextDocumentSnapshot(
                        "context-document-1", "stage-design", "source-run-1",
                        "设计方案", "动态 Stage 设计",
                        DocumentReference.of("agent-web", "docs/design.md"),
                        WorkbenchPersistenceFixtures.HASH_C,
                        WorkbenchContextDocumentContentState.CURRENT)),
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        WorkbenchPersistenceFixtures.HASH_D, 32)),
                promptPayload(runIdentifier).getPromptHash(),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", workspace.scope().getScopeHash(),
                        "agent-web", 1800L, 8_388_608L),
                Collections.emptyList(),
                Collections.singletonList(
                        VerifiedWorkbenchStageUploadedConversationAttachment
                                .restore(
                                        "stage-upload-1",
                                        new WorkbenchStageUploadedAttachmentBinding(
                                                WorkbenchPersistenceFixtures.OWNER,
                                                workbench.getId(),
                                                "stage-design",
                                                "stage-session-1", 2),
                                        "design.md", "text/markdown", 64L,
                                        WorkbenchPersistenceFixtures.HASH_B,
                                        WorkbenchPersistenceFixtures.HASH_C,
                                        "attachment-design.md",
                                        NOW.plusSeconds(3600), 4L)),
                NOW.plusSeconds(10));
    }

    private WorkbenchRunPromptPayload promptPayload(String runIdentifier) {
        return WorkbenchRunPromptPayload.freeze(
                runIdentifier, "Dynamic Stage private prompt",
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                NOW.plusSeconds(10));
    }

    private ResolvedCommandBinding commandBinding() {
        CommandDefinition definition = CommandDefinition.create(
                "architecture-review", "1.0.0", "Architecture Review",
                "Review the proposed architecture", "<module>",
                "Review architecture for $ARGUMENTS",
                "platform-commands", NOW);
        return definition.resolve(definition.getContentHash(), "module A");
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        ResolvedCommandBinding commandBinding = commandBinding();
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "阶段说明", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.singletonList(
                                new com.example.agentweb.domain.workbench.stage
                                        .StageCommandSelection(
                                        commandBinding.getIdentifier(),
                                        commandBinding.getVersion())),
                        Collections.emptyList(), Collections.emptyList()),
                StageCatalogEditor.create("admin-1", "Admin"), NOW);
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.singletonList(new StageCommandReference(
                                        commandBinding.getIdentifier(),
                                        commandBinding.getVersion(),
                                        commandBinding.getContentHash())),
                                Collections.emptyList(), Collections.emptyList()),
                        StageCatalogEditor.create("admin-1", "Admin"),
                        NOW.plusSeconds(1)));
    }
}
