package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.NoOpChatRunTerminalParticipant;
import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.workbench.port.WorkbenchTelemetry;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.ActiveRunReference;
import com.example.agentweb.domain.workbench.HandoffSnapshotReference;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.UploadedAttachmentContentSignature;
import com.example.agentweb.domain.workbench.UploadedAttachmentPolicy;
import com.example.agentweb.domain.workbench.UploadedConversationAttachment;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentStatus;
import com.example.agentweb.domain.workbench.UploadedConversationAttachmentRepository;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workbench ChatRun 首次终态参与者的仓储编排与严格绑定测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchChatRunTerminalParticipantTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    private WorkbenchRunSnapshotRepository snapshotRepository;
    private WorkbenchRepository workbenchRepository;
    private UploadedConversationAttachmentRepository attachmentRepository;
    private ChatRunRepository runRepository;
    private ChatRunEventAppender eventAppender;
    private WorkbenchTelemetry telemetry;
    private WorkbenchChatRunTerminalParticipant participant;

    @BeforeEach
    void setUp() {
        snapshotRepository = mock(WorkbenchRunSnapshotRepository.class);
        workbenchRepository = mock(WorkbenchRepository.class);
        attachmentRepository = mock(
                UploadedConversationAttachmentRepository.class);
        runRepository = mock(ChatRunRepository.class);
        eventAppender = mock(ChatRunEventAppender.class);
        telemetry = mock(WorkbenchTelemetry.class);
        participant = new WorkbenchChatRunTerminalParticipant(
                snapshotRepository, workbenchRepository,
                attachmentRepository,
                runRepository, eventAppender, telemetry);
    }

    @Test
    void matchingSnapshotShouldFinishWorkbenchAndPersistItInOrder() {
        Workbench workbench = preparedWorkbench(WORKBENCH_ID, "run-1");
        WorkbenchRunSnapshot snapshot = snapshot(WORKBENCH_ID, "run-1");
        when(snapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(snapshot));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ChatRun persistedTerminal = terminalRun("run-1");
        when(runRepository.findById(ChatRunId.of("run-1")))
                .thenReturn(Optional.of(persistedTerminal));
        ArgumentCaptor<Runnable> afterCommit =
                ArgumentCaptor.forClass(Runnable.class);
        long version = workbench.getVersion();

        participant.onFirstTerminal(ChatRunId.of("run-1"), NOW.plusSeconds(3));

        assertEquals(RunOrigin.WORKBENCH, participant.origin());
        assertNull(workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertNull(workbench.getActiveWriteRunReference());
        assertEquals(version + 1L, workbench.getVersion());
        InOrder order = inOrder(snapshotRepository, workbenchRepository);
        order.verify(snapshotRepository).findByRunId("run-1");
        order.verify(workbenchRepository).findById(WORKBENCH_ID);
        order.verify(workbenchRepository).update(workbench);
        verify(eventAppender).afterCommit(afterCommit.capture());
        verifyNoInteractions(telemetry);

        afterCommit.getValue().run();

        verify(telemetry).runTerminal(
                WorkbenchPhase.IMPLEMENT_TEST,
                RunMode.MODIFY_WORKSPACE, "FAILED",
                Duration.ofSeconds(1L));
    }

    @Test
    void missingSnapshotShouldFailClosedWithoutLoadingOrUpdatingWorkbench() {
        when(snapshotRepository.findByRunId("missing-run"))
                .thenReturn(Optional.<WorkbenchRunSnapshot>empty());

        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("missing-run"), NOW.plusSeconds(3)));

        verifyNoInteractions(workbenchRepository);
        verifyNoInteractions(runRepository, eventAppender, telemetry);
    }

    @Test
    void terminalRunShouldReleaseBoundUploadedAttachmentForCleanup() {
        Workbench workbench = preparedWorkbench(WORKBENCH_ID, "run-1");
        UploadedAttachmentPolicy policy = UploadedAttachmentPolicy.standard(
                1024L, 16, Duration.ofHours(24), Duration.ofHours(2));
        UploadedAttachmentBinding binding = new UploadedAttachmentBinding(
                OWNER, WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                "conversation-implement", 0);
        UploadedConversationAttachment attachment =
                UploadedConversationAttachment.upload(
                        "attachment-1", binding, "design.md",
                        "text/markdown",
                        UploadedAttachmentContentSignature.TEXT,
                        64L, repeat('7'), repeat('8'), policy, NOW);
        VerifiedUploadedConversationAttachment verified =
                attachment.verifyForRun(binding, repeat('7'), NOW.plusSeconds(1));
        attachment.bindToRun(verified, "run-1", NOW.plusSeconds(2), policy);
        WorkbenchRunSnapshot snapshot = snapshot(
                WORKBENCH_ID, "run-1", verified);
        when(snapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(snapshot));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(attachmentRepository.findById("attachment-1"))
                .thenReturn(Optional.of(attachment));

        participant.onFirstTerminal(
                ChatRunId.of("run-1"), NOW.plusSeconds(3));

        assertEquals(UploadedConversationAttachmentStatus.RELEASE_PENDING,
                attachment.getStatus());
        verify(attachmentRepository).update(attachment, 1L);
    }

    @Test
    void missingWorkbenchShouldFailClosedWithoutUpdate() {
        WorkbenchRunSnapshot snapshot = snapshot(WORKBENCH_ID, "run-1");
        when(snapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(snapshot));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.<Workbench>empty());

        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("run-1"), NOW.plusSeconds(3)));

        verify(workbenchRepository, never()).update(any(Workbench.class));
        verifyNoInteractions(runRepository, eventAppender, telemetry);
    }

    @Test
    void snapshotReturnedForAnotherRunShouldNotMutateOrUpdateWorkbench() {
        Workbench workbench = preparedWorkbench(WORKBENCH_ID, "bound-run");
        WorkbenchRunSnapshot snapshot = snapshot(WORKBENCH_ID, "bound-run");
        when(snapshotRepository.findByRunId("candidate-run"))
                .thenReturn(Optional.of(snapshot));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        ActiveRunReference phaseRun = workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference();
        ActiveRunReference writeLease = workbench.getActiveWriteRunReference();
        long version = workbench.getVersion();

        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("candidate-run"), NOW.plusSeconds(3)));

        assertSame(phaseRun, workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertSame(writeLease, workbench.getActiveWriteRunReference());
        assertEquals(version, workbench.getVersion());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verifyNoInteractions(runRepository, eventAppender, telemetry);
    }

    @Test
    void foreignWorkbenchReturnedByRepositoryShouldNotBeUpdated() {
        WorkbenchId foreignId = WorkbenchId.of("workbench-2");
        Workbench foreign = preparedWorkbench(foreignId, "run-1");
        WorkbenchRunSnapshot snapshot = snapshot(WORKBENCH_ID, "run-1");
        when(snapshotRepository.findByRunId("run-1"))
                .thenReturn(Optional.of(snapshot));
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(foreign));
        ActiveRunReference phaseRun = foreign.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference();
        long version = foreign.getVersion();

        assertRunBindingCorrupted(() -> participant.onFirstTerminal(
                ChatRunId.of("run-1"), NOW.plusSeconds(3)));

        assertSame(phaseRun, foreign.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertEquals(version, foreign.getVersion());
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verifyNoInteractions(runRepository, eventAppender, telemetry);
    }

    @Test
    void chatNoOpParticipantShouldDeclareChatOriginAndHaveNoSideEffects() {
        NoOpChatRunTerminalParticipant noOp =
                new NoOpChatRunTerminalParticipant();

        assertEquals(RunOrigin.CHAT, noOp.origin());
        assertDoesNotThrow(() -> noOp.onFirstTerminal(
                ChatRunId.of("chat-run"), NOW.plusSeconds(3)));
    }

    private static void assertRunBindingCorrupted(Runnable action) {
        WorkbenchDomainException error = assertThrows(
                WorkbenchDomainException.class, action::run);
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED, error.getCode());
    }

    private static Workbench preparedWorkbench(
            WorkbenchId workbenchId, String runId) {
        RepositoryScope scope = repositoryScope();
        Workbench workbench = Workbench.create(
                workbenchId, OWNER, "Workbench", "Implement terminal handling",
                AgentType.CODEX, "local", scope, snapshotReference(), NOW);
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "conversation-implement",
                OWNER, NOW.plusSeconds(1));
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, runId,
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(2));
        return workbench;
    }

    private static ChatRun terminalRun(String runId) {
        ChatRun run = ChatRun.submit(
                ChatRunId.of(runId), "conversation-implement", 1L,
                "submit-" + runId, false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        WORKBENCH_ID.getValue() + ":IMPLEMENT_TEST",
                        runId), NOW.plusSeconds(2));
        run.fail("TEST_FAILURE", "safe failure", Integer.valueOf(1),
                NOW.plusSeconds(3));
        return run;
    }

    private static WorkbenchRunSnapshot snapshot(
            WorkbenchId workbenchId, String runId) {
        return snapshot(workbenchId, runId, null);
    }

    private static WorkbenchRunSnapshot snapshot(
            WorkbenchId workbenchId, String runId,
            VerifiedUploadedConversationAttachment uploaded) {
        RepositoryScope scope = repositoryScope();
        return WorkbenchRunSnapshot.create(
                runId, workbenchId, WorkbenchPhase.IMPLEMENT_TEST,
                "submit-" + runId, repeat('7'), RunMode.MODIFY_WORKSPACE,
                scope, snapshotReference(), capabilityBinding(), null,
                HandoffSnapshotReference.of(
                        WorkbenchPhase.SOLUTION_DESIGN, 1L, repeat('2')),
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        Collections.singletonList("agent-web"), 1800L, 8388608L),
                Collections.emptyList(),
                uploaded == null ? Collections.emptyList()
                        : Collections.singletonList(uploaded),
                null, NOW.plusSeconds(2));
    }

    private static RepositoryScope repositoryScope() {
        return RepositoryScope.create(
                "/workspace", repositorySelection(),
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('8'), false)),
                10);
    }

    private static WorkspaceSnapshotReference snapshotReference() {
        return new WorkspaceSnapshotReference(
                "snapshot-1",
                WorkspaceTopology.of("/workspace", repositorySelection())
                        .getTopologyHash(),
                repeat('1'), 1);
    }

    private static RepositorySelection repositorySelection() {
        return RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
    }

    private static ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve(
                "policy-1", "implement-test", "1", repeat('a'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "platform", repeat('b'), true,
                        "Mandatory safety rule")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "codex-compatible");
    }

    private static String repeat(char value) {
        return String.join(
                "", Collections.nCopies(64, String.valueOf(value)));
    }
}
