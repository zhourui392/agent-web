package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDefinitionRevision;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_A;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_B;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dynamic Stage 上传附件聚合的真实 SQLite 生命周期测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteWorkbenchStageUploadedConversationAttachmentRepositoryTest {

    private static final String STAGE_INSTANCE_ID = "stage-design";
    private static final String SESSION_ID = "stage-session-upload";
    private static final UploadedAttachmentPolicy POLICY =
            UploadedAttachmentPolicy.standard(
                    1024L, 16, Duration.ofHours(24), Duration.ofHours(2));

    @TempDir
    Path tempDirectory;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private WorkbenchStageUploadedAttachmentBinding binding;
    private SqliteWorkbenchStageUploadedConversationAttachmentRepository
            repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDirectory.resolve("stage-uploaded-attachment.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDirectory,
                        "stage-uploaded-attachment-snapshot");
        workbench = dynamicWorkbench(workspace);
        workbench.bindStageConversation(
                STAGE_INSTANCE_ID, SESSION_ID, OWNER, NOW.plusSeconds(1));
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        binding = workbench.planStageUploadedAttachment(
                STAGE_INSTANCE_ID, 0, OWNER);
        repository =
                new SqliteWorkbenchStageUploadedConversationAttachmentRepository(
                        jdbc);
    }

    @Test
    void should_RoundTripBindReleaseAndCleanupWithOptimisticVersions() {
        // Given
        repository.add(attachment());
        WorkbenchStageUploadedConversationAttachment available = repository
                .findById("stage-attachment-1")
                .orElseThrow(AssertionError::new);

        // When
        VerifiedWorkbenchStageUploadedConversationAttachment verified =
                available.verifyForRun(
                        binding, HASH_A, NOW.plusSeconds(2));
        available.bindToRun(
                verified, "stage-run-1", NOW.plusSeconds(3), POLICY);
        repository.update(available, 0L);

        // Then
        WorkbenchStageUploadedConversationAttachment bound = repository
                .findById("stage-attachment-1")
                .orElseThrow(AssertionError::new);
        assertEquals(UploadedConversationAttachmentStatus.BOUND,
                bound.getStatus());
        assertEquals("stage-run-1", bound.getBoundRunId());
        assertEquals(0L, repository.countAvailable(
                binding, NOW.plusSeconds(4)));

        bound.releaseAfterTerminal("stage-run-1", NOW.plusSeconds(5));
        repository.update(bound, 1L);
        assertEquals(1, repository.findCleanupCandidates(
                NOW.plusSeconds(5), 10).size());
        repository.delete(bound);
        assertFalse(repository.findById("stage-attachment-1").isPresent());
    }

    @Test
    void should_PersistCancellationWithoutInventingBoundRun() {
        // Given
        WorkbenchStageUploadedConversationAttachment attachment = attachment();
        repository.add(attachment);

        // When
        attachment.cancelAvailable(binding, NOW.plusSeconds(2));
        repository.update(attachment, 0L);

        // Then
        WorkbenchStageUploadedConversationAttachment restored = repository
                .findById("stage-attachment-1")
                .orElseThrow(AssertionError::new);
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                restored.getStatus());
        assertEquals(null, restored.getBoundRunId());
    }

    @Test
    void should_FailClosedOnOwnerOrConversationBindingCorruption() {
        // Given
        repository.add(attachment());
        jdbc.update("UPDATE workbench SET owner_id = ?, owner_name = ? "
                        + "WHERE id = ?",
                "another-owner", "Another",
                workbench.getId().getValue());

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> repository.findById("stage-attachment-1"));

        List<Map<String, Object>> columns = jdbc.queryForList(
                "PRAGMA table_info(workbench_stage_uploaded_attachment)");
        for (Map<String, Object> column : columns) {
            String name = String.valueOf(column.get("name")).toLowerCase();
            assertFalse("phase".equals(name));
            assertFalse(name.contains("secret"));
            assertFalse(name.contains("token"));
        }
    }

    private WorkbenchStageUploadedConversationAttachment attachment() {
        return WorkbenchStageUploadedConversationAttachment.upload(
                "stage-attachment-1", binding, "design.md",
                "text/markdown", UploadedAttachmentContentSignature.TEXT,
                64L, HASH_A, HASH_B, POLICY, NOW.plusSeconds(1));
    }

    private Workbench dynamicWorkbench(
            WorkbenchPersistenceFixtures.WorkspaceFixture workspace) {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成方案", "阶段规则",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(2));
        WorkbenchStageDefinitionRevision revision = catalog.publishDraft(
                "solution-design", catalog.getCatalogVersion(), 1L,
                new ResolvedStageCapabilities(
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(1));
        WorkbenchStageSnapshot snapshot =
                WorkbenchStageSnapshot.fromPublishedRevision(revision);
        return Workbench.create(
                WorkbenchId.of("workbench-stage-uploaded-attachment"),
                OWNER, "Dynamic Workbench", "测试 Stage 附件",
                AgentType.CODEX, "local", workspace.scope(),
                workspace.snapshot().reference(),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_ID, snapshot)), NOW);
    }
}
