package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.attachment.port.StoredUploadedAttachment;
import com.example.agentweb.app.workbench.attachment.port.UploadedAttachmentStorageRequest;
import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentQueryService;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传附件应用编排、Owner 隔离、Quota 与失败清理测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class UploadedConversationAttachmentAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-upload-app");

    private WorkbenchRepository workbenchRepository;
    private UploadedConversationAttachmentRepository attachmentRepository;
    private UploadedConversationAttachmentQueryService attachmentQueryService;
    private UploadedConversationAttachmentStorage storage;
    private UploadedAttachmentIdGenerator idGenerator;
    private UploadedConversationAttachmentAppService service;
    private Workbench workbench;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        attachmentRepository = mock(UploadedConversationAttachmentRepository.class);
        attachmentQueryService = mock(
                UploadedConversationAttachmentQueryService.class);
        storage = mock(UploadedConversationAttachmentStorage.class);
        idGenerator = mock(UploadedAttachmentIdGenerator.class);
        service = new UploadedConversationAttachmentAppService(
                workbenchRepository, attachmentRepository,
                attachmentQueryService, storage, idGenerator,
                UploadedAttachmentPolicy.standard(
                        10L * 1024L * 1024L, 16,
                        Duration.ofHours(24), Duration.ofHours(2)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        workbench = workbench();
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", OWNER, NOW.minusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(idGenerator.nextId()).thenReturn("attachment-1");
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldStoreThenPersistOnlySafeLogicalProjection() {
        byte[] content = "# Design".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(storage.store(any(UploadedAttachmentStorageRequest.class)))
                .thenReturn(new StoredUploadedAttachment(
                        repeat('a'), repeat('b'), content.length,
                        UploadedAttachmentContentSignature.TEXT));

        UploadedConversationAttachmentView view = service.upload(
                OWNER, new UploadConversationAttachmentCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0, " design.md ", "text/markdown", content.length),
                new ByteArrayInputStream(content));

        assertEquals("attachment-1", view.getAttachmentId());
        assertEquals("design.md", view.getDisplayName());
        assertEquals("text/markdown", view.getMediaType());
        assertEquals(content.length, view.getSize());
        assertEquals(repeat('b'), view.getSha256());
        assertEquals(NOW.plus(Duration.ofHours(24)), view.getExpiresAt());
        assertFalse(view.toString().contains(repeat('a')));
        verify(attachmentRepository).add(any(UploadedConversationAttachment.class));
    }

    @Test
    void foreignOwnerAndQuotaShouldFailBeforeStorage() {
        assertThrows(WorkbenchNotFoundException.class, () -> service.upload(
                OwnerReference.of("other", "Other"), command(),
                new ByteArrayInputStream(new byte[] {1})));
        when(attachmentQueryService.countAvailable(any(), any()))
                .thenReturn(16L);
        assertThrows(WorkbenchDomainException.class, () -> service.upload(
                OWNER, command(), new ByteArrayInputStream(new byte[] {1})));

        verify(storage, never()).store(any());
    }

    @Test
    void invalidStoredMediaShouldDiscardOpaqueStorageKey() {
        when(storage.store(any())).thenReturn(new StoredUploadedAttachment(
                repeat('a'), repeat('b'), 20L,
                UploadedAttachmentContentSignature.PE_EXECUTABLE));

        assertThrows(WorkbenchDomainException.class, () -> service.upload(
                OWNER, new UploadConversationAttachmentCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0, "payload.txt", "text/plain", 20L),
                new ByteArrayInputStream(new byte[20])));

        verify(storage).delete(repeat('a'));
        verify(attachmentRepository, never()).add(any());
    }

    @Test
    void transactionRollbackAfterReturnShouldDiscardStoredObject() {
        byte[] content = "approved".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        when(storage.store(any())).thenReturn(new StoredUploadedAttachment(
                repeat('a'), repeat('b'), content.length,
                UploadedAttachmentContentSignature.TEXT));
        TransactionSynchronizationManager.initSynchronization();

        service.upload(
                OWNER, new UploadConversationAttachmentCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0, "design.md", "text/markdown", content.length),
                new ByteArrayInputStream(content));
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(storage).delete(repeat('a'));
    }

    @Test
    void transactionCommitShouldRetainStoredObject() {
        byte[] content = "approved".getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
        when(storage.store(any())).thenReturn(new StoredUploadedAttachment(
                repeat('a'), repeat('b'), content.length,
                UploadedAttachmentContentSignature.TEXT));
        TransactionSynchronizationManager.initSynchronization();

        service.upload(
                OWNER, new UploadConversationAttachmentCommand(
                        WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0, "design.md", "text/markdown", content.length),
                new ByteArrayInputStream(content));
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
        }

        verify(storage, never()).delete(any());
    }

    @Test
    void cancelShouldAuthorizeCurrentBindingAndPersistReleasePending() {
        UploadedConversationAttachment attachment =
                UploadedConversationAttachment.upload(
                        "attachment-1", new UploadedAttachmentBinding(
                        OWNER, WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "conversation-1", 0),
                        "design.md", "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        8L, repeat('b'), repeat('a'),
                        UploadedAttachmentPolicy.standard(
                                1024L, 16, Duration.ofHours(24),
                                Duration.ofHours(2)), NOW);
        when(attachmentRepository.findById("attachment-1"))
                .thenReturn(Optional.of(attachment));

        service.cancel(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0, "attachment-1");

        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        verify(attachmentRepository).update(attachment, 0L);
        verify(storage, never()).delete(any());
    }

    private UploadConversationAttachmentCommand command() {
        return new UploadConversationAttachmentCommand(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                0, "design.md", "text/markdown", 1L);
    }

    private Workbench workbench() {
        RepositoryScope scope = RepositoryScope.create(
                "/workspace",
                RepositorySelection.of("agent-web",
                        Collections.singletonList("agent-web")),
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('c'), false)), 8);
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace", RepositorySelection.of(
                        "agent-web", Collections.singletonList("agent-web")));
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Upload", "Analyze file",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(), repeat('d'), 1),
                NOW.minusSeconds(2));
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }
}
