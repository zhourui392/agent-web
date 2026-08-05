package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.NoOpChatRunTerminalParticipant;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Workbench ChatRun 首次终态编排与严格绑定测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchChatRunTerminalParticipantTest {

    private WorkbenchStageRunSnapshotRepository snapshotRepository;
    private WorkbenchRepository workbenchRepository;
    private WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    private ChatRunRepository runRepository;
    private ChatRunEventAppender eventAppender;
    private WorkbenchTelemetry telemetry;
    private WorkbenchChatRunTerminalParticipant participant;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(
                WorkbenchStageRunSnapshotRepository.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        attachmentRepository = mock(
                WorkbenchStageUploadedConversationAttachmentRepository.class);
        runRepository = mock(ChatRunRepository.class);
        eventAppender = mock(ChatRunEventAppender.class);
        telemetry = mock(WorkbenchTelemetry.class);
        participant = new WorkbenchChatRunTerminalParticipant(
                snapshotRepository, workbenchRepository,
                attachmentRepository, runRepository,
                eventAppender, telemetry);
    }

    @Test
    void should_FinishStageRunAndRecordPersistedTerminal_When_BindingMatches() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                preparedFixtureWithUpload();
        Workbench workbench = fixture.workbench();
        WorkbenchStageUploadedConversationAttachment attachment =
                fixture.uploadedAttachment();
        when(snapshotRepository.findByRunId(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(Optional.of(fixture.snapshot()));
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(attachmentRepository.findById("stage-attachment-1"))
                .thenReturn(Optional.of(attachment));
        ChatRun persistedTerminal = terminalRun(
                WorkbenchStageRunTestFixtures.contextIdentifier());
        when(runRepository.findById(ChatRunId.of(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER)))
                .thenReturn(Optional.of(persistedTerminal));
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);
        long version = workbench.getVersion();

        // When
        participant.onFirstTerminal(
                ChatRunId.of(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(5));

        // Then
        assertEquals(RunOrigin.WORKBENCH, participant.origin());
        assertNull(workbench.stage(
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER)
                .getActiveRunReference());
        assertNull(workbench.getActiveWriteRunReference());
        assertEquals(version + 1L, workbench.getVersion());
        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        verify(attachmentRepository).update(attachment, 1L);
        InOrder order = inOrder(snapshotRepository, workbenchRepository);
        order.verify(snapshotRepository).findByRunId(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER);
        order.verify(workbenchRepository).findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID);
        order.verify(workbenchRepository).update(workbench);
        verify(eventAppender).afterCommit(afterCommit.capture());
        verifyNoInteractions(telemetry);

        afterCommit.getValue().run();

        verify(telemetry).runTerminal(
                RunMode.MODIFY_WORKSPACE, "FAILED",
                Duration.ofSeconds(1L));
    }

    @Test
    void should_FailClosedWithoutWorkbenchLookup_When_SnapshotIsMissing() {
        when(snapshotRepository.findByRunId("missing-run"))
                .thenReturn(Optional.empty());

        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("missing-run"),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(5)));

        verifyNoInteractions(workbenchRepository, attachmentRepository,
                runRepository, eventAppender, telemetry);
    }

    @Test
    void should_NotMutateWorkbench_When_SnapshotRunIdentifierDiffers() {
        // Given
        WorkbenchStageRunTestFixtures.Fixture fixture =
                preparedFixtureWithUpload();
        Workbench workbench = fixture.workbench();
        when(snapshotRepository.findByRunId("candidate-run"))
                .thenReturn(Optional.of(fixture.snapshot()));
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        long version = workbench.getVersion();

        // When / Then
        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("candidate-run"),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(5)));
        assertEquals(version, workbench.getVersion());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verifyNoInteractions(attachmentRepository, runRepository,
                eventAppender, telemetry);
    }

    @Test
    void should_DeclareChatNoOpParticipantWithoutSideEffects_When_ChatOrigin() {
        NoOpChatRunTerminalParticipant noOp =
                new NoOpChatRunTerminalParticipant();

        assertEquals(RunOrigin.CHAT, noOp.origin());
        assertDoesNotThrow(() -> noOp.onFirstTerminal(
                ChatRunId.of("chat-run"),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(5)));
    }

    private static WorkbenchStageRunTestFixtures.Fixture
            preparedFixtureWithUpload() {
        WorkbenchStageRunTestFixtures.Fixture fixture =
                WorkbenchStageRunTestFixtures.withModifyUpload();
        fixture.workbench().prepareStageRun(
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                RunMode.MODIFY_WORKSPACE,
                WorkbenchStageRunTestFixtures.OWNER,
                fixture.workbench().getVersion(),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(3));
        fixture.uploadedAttachment().bindToRun(
                fixture.snapshot().getVerifiedUploadedAttachments().get(0),
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(3),
                UploadedAttachmentPolicy.standard(
                        1024L, 8, Duration.ofHours(24),
                        Duration.ofHours(2)));
        return fixture;
    }

    private static ChatRun terminalRun(String originReference) {
        ChatRun run = ChatRun.submit(
                ChatRunId.of(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.SESSION_IDENTIFIER, 1L,
                "submit-stage-run", false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        originReference,
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(3));
        run.fail("TEST_FAILURE", "safe failure", Integer.valueOf(1),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(4));
        return run;
    }

    private static void assertRunBindingCorrupted(Runnable action) {
        WorkbenchDomainException error = assertThrows(
                WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                error.getCode());
    }
}
