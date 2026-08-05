package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.stage.ResolvedStageCapabilities;
import com.example.agentweb.domain.workbench.stage.StageCatalogEditor;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCatalog;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageDraftContent;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageSnapshot;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageState;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage 上传附件的应用编排与外部存储补偿测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageUploadedConversationAttachmentAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T15:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-stage-upload-app");
    private static final String STAGE_INSTANCE_IDENTIFIER = "stage-design";
    private static final String CONVERSATION_IDENTIFIER = "stage-session-1";

    @Mock private WorkbenchRepository workbenchRepository;
    @Mock private WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    @Mock private WorkbenchStageUploadedConversationAttachmentQueryService
            attachmentQueryService;
    @Mock private UploadedConversationAttachmentStorage storage;
    @Mock private UploadedAttachmentIdGenerator identifierGenerator;
    @Spy private UploadedAttachmentPolicy policy =
            UploadedAttachmentPolicy.standard(
                    1024L, 8, Duration.ofHours(24), Duration.ofHours(2));
    @Mock private Clock clock;

    @InjectMocks
    private WorkbenchStageUploadedConversationAttachmentAppService service;

    private Workbench workbench;

    @BeforeEach
    void setUp() {
        workbench = dynamicWorkbench();
        workbench.bindStageConversation(
                STAGE_INSTANCE_IDENTIFIER, CONVERSATION_IDENTIFIER,
                OWNER, 0L, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void should_StoreAndPersistExactCurrentStageConversationBinding() {
        // Given
        when(clock.instant()).thenReturn(NOW);
        byte[] content = "# Design".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        when(identifierGenerator.nextId()).thenReturn("stage-attachment-1");
        when(storage.store(any(UploadedAttachmentStorageRequest.class)))
                .thenReturn(new StoredUploadedAttachment(
                        repeat('a'), repeat('b'), content.length,
                        UploadedAttachmentContentSignature.TEXT));

        // When
        WorkbenchStageUploadedConversationAttachmentView view =
                service.upload(
                        OWNER, command(content.length),
                        new ByteArrayInputStream(content));

        // Then
        assertEquals("stage-attachment-1", view.getAttachmentId());
        assertEquals("design.md", view.getDisplayName());
        assertEquals(repeat('b'), view.getSha256());
        assertFalse(view.toString().contains(repeat('a')));
        ArgumentCaptor<WorkbenchStageUploadedConversationAttachment> saved =
                ArgumentCaptor.forClass(
                        WorkbenchStageUploadedConversationAttachment.class);
        verify(attachmentRepository).add(saved.capture());
        assertEquals(STAGE_INSTANCE_IDENTIFIER,
                saved.getValue().getStageInstanceIdentifier());
        assertEquals(CONVERSATION_IDENTIFIER,
                saved.getValue().getConversationId());
        assertEquals(0, saved.getValue().getConversationGeneration());
    }

    @Test
    void should_RejectForeignOwnerAndOldGenerationBeforeStorage() {
        // Given
        UploadWorkbenchStageConversationAttachmentCommand oldGeneration =
                new UploadWorkbenchStageConversationAttachmentCommand(
                        WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER, 1,
                        "design.md", "text/markdown", 1L);

        // When / Then
        assertThrows(WorkbenchNotFoundException.class,
                () -> service.upload(
                        OwnerReference.of("other", "Other"), command(1L),
                        new ByteArrayInputStream(new byte[] {1})));
        assertThrows(WorkbenchDomainException.class,
                () -> service.upload(
                        OWNER, oldGeneration,
                        new ByteArrayInputStream(new byte[] {1})));
        verify(storage, never()).store(any());
    }

    @Test
    void should_DiscardStoredObjectWhenPersistenceFailsOrTransactionRollsBack() {
        // Given
        when(clock.instant()).thenReturn(NOW);
        byte[] content = "design".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        when(identifierGenerator.nextId()).thenReturn("stage-attachment-1");
        when(storage.store(any())).thenReturn(new StoredUploadedAttachment(
                repeat('a'), repeat('b'), content.length,
                UploadedAttachmentContentSignature.TEXT));
        doThrow(new IllegalStateException("database unavailable"))
                .when(attachmentRepository).add(any());

        // When / Then
        assertThrows(IllegalStateException.class,
                () -> service.upload(
                        OWNER, command(content.length),
                        new ByteArrayInputStream(content)));
        verify(storage).delete(repeat('a'));

        org.mockito.Mockito.reset(attachmentRepository, storage);
        when(storage.store(any())).thenReturn(new StoredUploadedAttachment(
                repeat('c'), repeat('b'), content.length,
                UploadedAttachmentContentSignature.TEXT));
        TransactionSynchronizationManager.initSynchronization();
        service.upload(
                OWNER, command(content.length),
                new ByteArrayInputStream(content));
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(storage).delete(repeat('c'));
    }

    @Test
    void should_CancelOnlyAttachmentFromCurrentStageConversationGeneration() {
        // Given
        when(clock.instant()).thenReturn(NOW);
        WorkbenchStageUploadedConversationAttachment attachment =
                WorkbenchStageUploadedConversationAttachment.upload(
                        "stage-attachment-1",
                        workbench.planStageUploadedAttachment(
                                STAGE_INSTANCE_IDENTIFIER, 0, OWNER),
                        "design.md", "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        8L, repeat('b'), repeat('a'), policy, NOW);
        when(attachmentRepository.findById("stage-attachment-1"))
                .thenReturn(Optional.of(attachment));

        // When
        service.cancel(
                OWNER, WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER,
                0, "stage-attachment-1");

        // Then
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        verify(attachmentRepository).update(attachment, 0L);
        verify(storage, never()).delete(any());
    }

    private UploadWorkbenchStageConversationAttachmentCommand command(
            long declaredSize) {
        return new UploadWorkbenchStageConversationAttachmentCommand(
                WORKBENCH_ID, STAGE_INSTANCE_IDENTIFIER, 0,
                " design.md ", "text/markdown", declaredSize);
    }

    private Workbench dynamicWorkbench() {
        RepositoryScope scope = repositoryScope();
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")));
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Dynamic Workbench", "Design feature",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(), repeat('d'), 1),
                Collections.singletonList(WorkbenchStageState.initial(
                        STAGE_INSTANCE_IDENTIFIER, stageSnapshot())),
                NOW.minusSeconds(2));
    }

    private WorkbenchStageSnapshot stageSnapshot() {
        WorkbenchStageCatalog catalog = WorkbenchStageCatalog.empty();
        StageCatalogEditor administrator =
                StageCatalogEditor.create("admin-1", "Admin");
        catalog.createDraft(
                "solution-design",
                WorkbenchStageDraftContent.create(
                        20, "方案设计", "形成完整方案", "保持领域边界清晰",
                        Set.of(RunMode.DISCUSS_READ_ONLY),
                        Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList()),
                administrator, NOW.minusSeconds(3));
        return WorkbenchStageSnapshot.fromPublishedRevision(
                catalog.publishDraft(
                        "solution-design", catalog.getCatalogVersion(), 1L,
                        new ResolvedStageCapabilities(
                                Collections.emptyList(),
                                Collections.emptyList(),
                                Collections.emptyList()),
                        administrator, NOW.minusSeconds(2)));
    }

    private RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('c'), false)),
                8);
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
