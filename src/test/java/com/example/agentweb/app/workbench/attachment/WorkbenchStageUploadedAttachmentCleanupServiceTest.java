package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage 上传附件物理对象和聚合行的清理测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageUploadedAttachmentCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-05T16:00:00Z");

    @Mock private WorkbenchStageUploadedConversationAttachmentRepository
            repository;
    @Mock private UploadedConversationAttachmentStorage storage;
    @Mock private UploadedAttachmentCleanupSettings settings;
    @Mock private Clock clock;

    @InjectMocks
    private WorkbenchStageUploadedAttachmentCleanupService service;

    private WorkbenchStageUploadedConversationAttachment candidate;

    @BeforeEach
    void setUp() {
        candidate = cancelledAttachment();
        when(clock.instant()).thenReturn(NOW);
        when(settings.getBatchSize()).thenReturn(20);
        when(repository.findCleanupCandidates(NOW, 20))
                .thenReturn(Collections.singletonList(candidate));
    }

    @Test
    void should_DeletePhysicalObjectBeforeDeletingEligibleAggregate() {
        // When
        service.cleanup();

        // Then
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                storage, repository);
        order.verify(storage).delete(candidate.getStorageKey());
        order.verify(repository).delete(candidate);
    }

    @Test
    void should_KeepAggregateForRetryWhenPhysicalDeletionFails() {
        // Given
        doThrow(new UploadedAttachmentStorageException("unavailable"))
                .when(storage).delete(candidate.getStorageKey());

        // When / Then
        assertThrows(UploadedAttachmentStorageException.class,
                service::cleanup);
        verify(repository, never()).delete(candidate);
    }

    private WorkbenchStageUploadedConversationAttachment
            cancelledAttachment() {
        WorkbenchStageUploadedAttachmentBinding binding =
                new WorkbenchStageUploadedAttachmentBinding(
                        OwnerReference.of("owner-1", "Alex"),
                        WorkbenchId.of("workbench-1"), "stage-design",
                        "stage-session-1", 0);
        WorkbenchStageUploadedConversationAttachment attachment =
                WorkbenchStageUploadedConversationAttachment.upload(
                        "stage-attachment-1", binding, "design.md",
                        "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        64L, repeat('a'), repeat('b'),
                        UploadedAttachmentPolicy.standard(
                                1024L, 8, Duration.ofHours(24),
                                Duration.ofHours(2)),
                        NOW.minusSeconds(1));
        attachment.cancelAvailable(binding, NOW);
        return attachment;
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
