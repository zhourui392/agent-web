package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageRunSnapshotRepository;
import com.example.agentweb.domain.workbench.WorkbenchStageUploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageStatus;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Dynamic Stage Run 原子提交、幂等与附件绑定编排测试。
 *
 * @author alex
 * @since 2026-08-05
 */
@ExtendWith(MockitoExtension.class)
class WorkbenchStageRunSubmissionCommitterTest {

    @Mock private WorkbenchRepository workbenchRepository;
    @Mock private WorkspaceSnapshotRepository workspaceSnapshotRepository;
    @Mock private WorkbenchStageRunSnapshotRepository snapshotRepository;
    @Mock private WorkbenchStageRunPromptPayloadRepository promptRepository;
    @Mock private WorkbenchStageUploadedConversationAttachmentRepository
            attachmentRepository;
    @Spy private UploadedAttachmentPolicy attachmentPolicy =
            UploadedAttachmentPolicy.standard(
                    1024L, 8, Duration.ofHours(24), Duration.ofHours(2));
    @Mock private SessionRepository sessionRepository;
    @Mock private ChatRunRepository runRepository;
    @Mock private ChatRunEventAppender eventAppender;
    @Mock private ChatRunLauncher launcher;
    @Mock private ChatRunActivityGuard activityGuard;
    @Mock private ChatRunQueryService runQueryService;
    @Mock private ChatRunStreamSettings streamSettings;
    @Mock private WorkbenchStageRunSubmissionExecutor submissionExecutor;
    @Mock private Clock clock;

    @InjectMocks
    private WorkbenchStageRunSubmissionCommitter committer;

    private WorkbenchStageRunTestFixtures.Fixture fixture;

    @BeforeEach
    void setUp() {
        fixture = WorkbenchStageRunTestFixtures.withoutUpload();
        when(submissionExecutor.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Supplier<WorkbenchStageRunSubmissionResult> action =
                    invocation.getArgument(0, Supplier.class);
            return action.get();
        });
    }

    @Test
    void should_PersistDynamicFactsAndRegisterRuntimeLaunchAfterCommit()
            throws Exception {
        // Given
        stubFirstSubmission(fixture);

        // When
        WorkbenchStageRunSubmissionResult result = committer.commit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.prepared());

        // Then
        assertEquals(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                result.getRunId());
        assertEquals(WorkbenchStageRunTestFixtures.SESSION_IDENTIFIER,
                result.getSessionId());
        assertEquals(ChatRunStatus.PENDING, result.getStatus());
        assertEquals(WorkbenchStageStatus.IN_PROGRESS,
                result.getStageStatus());
        assertFalse(result.isReplayed());

        ArgumentCaptor<ChatRun> run =
                ArgumentCaptor.forClass(ChatRun.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ChatRunEventDraft>> events =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(eventAppender).appendToNewRun(
                run.capture(), events.capture(),
                org.mockito.ArgumentMatchers.eq(
                        WorkbenchStageRunTestFixtures.NOW.plusSeconds(3)));
        assertEquals(RunOrigin.WORKBENCH, run.getValue().getRunOrigin());
        assertEquals(ExecutionContextReference.of(
                        WorkbenchStageRunTestFixtures.contextIdentifier(),
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                run.getValue().getExecutionContextReference());
        JsonNode eventPayload = new ObjectMapper().readTree(
                events.getValue().get(0).getPayload());
        assertEquals(WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                eventPayload.path("stageInstanceIdentifier").asText());
        assertFalse(eventPayload.has("phase"));

        InOrder order = inOrder(
                eventAppender, workspaceSnapshotRepository,
                snapshotRepository, promptRepository,
                workbenchRepository);
        order.verify(eventAppender).appendToNewRun(any(), any(), any());
        order.verify(workspaceSnapshotRepository).add(
                fixture.workspaceSnapshot());
        order.verify(snapshotRepository).add(fixture.snapshot());
        order.verify(promptRepository).add(fixture.promptPayload());
        order.verify(workbenchRepository).update(fixture.workbench());
        ArgumentCaptor<Runnable> launch =
                ArgumentCaptor.forClass(Runnable.class);
        order.verify(eventAppender).afterCommit(launch.capture());
        verifyNoInteractions(launcher);

        launch.getValue().run();

        verify(launcher).launch(ChatRunId.of(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER));
    }

    @Test
    void should_ReplayExactDynamicSubmissionWithoutRepeatingWrites() {
        // Given
        ChatRun existingRun = existingRun(fixture);
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(fixture.workbench()));
        when(snapshotRepository.findByWorkbenchStageAndIdempotencyKey(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                fixture.command().getIdempotencyKey()))
                .thenReturn(Optional.of(fixture.snapshot()));
        when(promptRepository.findByRunId(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER))
                .thenReturn(Optional.of(fixture.promptPayload()));
        when(runRepository.findById(ChatRunId.of(
                WorkbenchStageRunTestFixtures.RUN_IDENTIFIER)))
                .thenReturn(Optional.of(existingRun));

        // When
        WorkbenchStageRunSubmissionResult result = committer.commit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.prepared());

        // Then
        assertTrue(result.isReplayed());
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never()).appendToNewRun(any(), any(), any());
        verify(snapshotRepository, never()).add(any());
        verify(promptRepository, never()).add(any());
        verify(workbenchRepository, never()).update(any());
        verify(eventAppender, never()).afterCommit(any());
        verifyNoInteractions(launcher);
    }

    @Test
    void should_BindExactStageUploadedAttachmentInsideTransaction() {
        // Given
        fixture = WorkbenchStageRunTestFixtures.withUpload();
        stubFirstSubmission(fixture);
        when(attachmentRepository.findById("stage-attachment-1"))
                .thenReturn(Optional.of(fixture.uploadedAttachment()));

        // When
        committer.commit(
                WorkbenchStageRunTestFixtures.OWNER, fixture.prepared());

        // Then
        assertEquals(UploadedConversationAttachmentStatus.BOUND,
                fixture.uploadedAttachment().getStatus());
        assertEquals(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER,
                fixture.uploadedAttachment().getBoundRunId());
        verify(attachmentRepository).update(
                fixture.uploadedAttachment(), 0L);
    }

    private void stubFirstSubmission(
            WorkbenchStageRunTestFixtures.Fixture source) {
        when(workbenchRepository.findById(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID))
                .thenReturn(Optional.of(source.workbench()));
        when(snapshotRepository.findByWorkbenchStageAndIdempotencyKey(
                WorkbenchStageRunTestFixtures.WORKBENCH_ID,
                WorkbenchStageRunTestFixtures.STAGE_INSTANCE_IDENTIFIER,
                source.command().getIdempotencyKey()))
                .thenReturn(Optional.empty());
        when(sessionRepository.findById(
                WorkbenchStageRunTestFixtures.SESSION_IDENTIFIER))
                .thenReturn(source.session());
        when(sessionRepository.addMessageReturningId(any(), any()))
                .thenReturn(41L);
        when(runQueryService.countActiveRuns()).thenReturn(0L);
        when(streamSettings.getMaxActiveRuns()).thenReturn(4);
        when(clock.instant()).thenReturn(
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(3));
    }

    private ChatRun existingRun(
            WorkbenchStageRunTestFixtures.Fixture source) {
        return ChatRun.submit(
                ChatRunId.of(WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.SESSION_IDENTIFIER, 41L,
                source.command().getIdempotencyKey(), false,
                RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        WorkbenchStageRunTestFixtures.contextIdentifier(),
                        WorkbenchStageRunTestFixtures.RUN_IDENTIFIER),
                WorkbenchStageRunTestFixtures.NOW.plusSeconds(3));
    }
}
