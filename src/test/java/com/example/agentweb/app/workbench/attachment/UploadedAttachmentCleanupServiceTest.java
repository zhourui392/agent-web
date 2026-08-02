package com.example.agentweb.app.workbench.attachment;

import com.example.agentweb.app.workbench.attachment.port.UploadedConversationAttachmentStorage;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 上传附件物理文件与聚合行的幂等清理编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class UploadedAttachmentCleanupServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void shouldDeletePhysicalObjectBeforeDeletingEligibleAggregateRow() {
        UploadedConversationAttachmentRepository repository =
                mock(UploadedConversationAttachmentRepository.class);
        UploadedConversationAttachmentStorage storage =
                mock(UploadedConversationAttachmentStorage.class);
        UploadedConversationAttachment candidate = cancelled();
        when(repository.findCleanupCandidates(NOW, 20))
                .thenReturn(Collections.singletonList(candidate));
        UploadedAttachmentCleanupService service = service(
                repository, storage);

        service.cleanup();

        verify(storage).delete(candidate.getStorageKey());
        verify(repository).delete(candidate);
    }

    @Test
    void storageFailureShouldKeepDatabaseRowForScheduledRetry() {
        UploadedConversationAttachmentRepository repository =
                mock(UploadedConversationAttachmentRepository.class);
        UploadedConversationAttachmentStorage storage =
                mock(UploadedConversationAttachmentStorage.class);
        UploadedConversationAttachment candidate = cancelled();
        when(repository.findCleanupCandidates(NOW, 20))
                .thenReturn(Collections.singletonList(candidate));
        doThrow(new UploadedAttachmentStorageException("unavailable"))
                .when(storage).delete(candidate.getStorageKey());
        UploadedAttachmentCleanupService service = service(
                repository, storage);

        assertThrows(UploadedAttachmentStorageException.class,
                service::cleanup);

        verify(repository, never()).delete(candidate);
    }

    private UploadedAttachmentCleanupService service(
            UploadedConversationAttachmentRepository repository,
            UploadedConversationAttachmentStorage storage) {
        return new UploadedAttachmentCleanupService(
                repository, storage,
                new UploadedAttachmentCleanupSettings(20),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private UploadedConversationAttachment cancelled() {
        UploadedAttachmentBinding binding = new UploadedAttachmentBinding(
                OwnerReference.of("owner-1", "Alex"),
                WorkbenchId.of("workbench-1"),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", 0);
        UploadedConversationAttachment attachment =
                UploadedConversationAttachment.upload(
                        "attachment-1", binding, "design.md",
                        "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        64L, repeat('a'), repeat('b'),
                        UploadedAttachmentPolicy.standard(
                                1024L, 16, Duration.ofHours(24),
                                Duration.ofHours(2)), NOW.minusSeconds(1));
        attachment.cancelAvailable(binding, NOW);
        return attachment;
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, Character.toString(value)));
    }
}
