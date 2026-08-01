package com.example.agentweb.app.workbench.conversation;

import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionKind;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseConversationProvisioning;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceipt;
import com.example.agentweb.domain.workbench.PhaseConversationRestartReceiptRepository;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase Conversation 创建、核验、重启与幂等的 Application 编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseConversationAppServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-01T10:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("owner-1", "Alex");
    private static final OwnerReference FOREIGN = OwnerReference.of("owner-2", "Other");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final WorkbenchPhase PHASE = WorkbenchPhase.IMPLEMENT_TEST;

    private WorkbenchRepository workbenchRepository;
    private SessionRepository sessionRepository;
    private PhaseConversationRestartReceiptRepository receiptRepository;
    private PhaseSessionIdGenerator sessionIdGenerator;
    private PhaseConversationAppService service;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        sessionRepository = mock(SessionRepository.class);
        receiptRepository = mock(PhaseConversationRestartReceiptRepository.class);
        sessionIdGenerator = mock(PhaseSessionIdGenerator.class);
        service = new PhaseConversationAppService(
                workbenchRepository, sessionRepository, receiptRepository,
                sessionIdGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void ensureConversationShouldCreateServerOwnedPhaseSessionAndPersistBothAggregates() {
        Workbench workbench = newWorkbench();
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionIdGenerator.nextId()).thenReturn("phase-session-1");

        PhaseConversationResult result = service.ensureConversation(
                OWNER, WORKBENCH_ID, PHASE, 0L);

        assertTrue(result.isCreated());
        assertFalse(result.isReplayed());
        assertEquals("phase-session-1", result.getSessionId());
        assertNull(result.getPreviousSessionId());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(1L, result.getWorkbenchVersion());
        ArgumentCaptor<ChatSession> sessionCaptor = ArgumentCaptor.forClass(ChatSession.class);
        InOrder persisted = inOrder(sessionRepository, workbenchRepository);
        persisted.verify(sessionRepository).addSession(sessionCaptor.capture());
        persisted.verify(workbenchRepository).update(workbench);
        ChatSession session = sessionCaptor.getValue();
        assertEquals(SessionKind.WORKBENCH_PHASE, session.getSessionKind());
        assertEquals("workbench-1:IMPLEMENT_TEST", session.getContextId());
        assertEquals("owner-1", session.getUserId());
        assertEquals("Alex", session.getUserName());
        assertEquals(AgentType.CODEX, session.getAgentType());
        assertEquals("/workspace/agent-web", session.getWorkingDir());
        assertEquals("local", session.getEnv());
        assertEquals(NOW, session.getCreatedAt());
    }

    @Test
    void ensureConversationShouldCanonicalizeSubMillisecondClockForExactPersistedFacts() {
        Instant preciseNow = Instant.parse("2026-08-01T10:00:00.123456789Z");
        Instant persistedNow = Instant.parse("2026-08-01T10:00:00.123Z");
        service = new PhaseConversationAppService(
                workbenchRepository, sessionRepository, receiptRepository,
                sessionIdGenerator, Clock.fixed(preciseNow, ZoneOffset.UTC));
        Workbench workbench = newWorkbench();
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(sessionIdGenerator.nextId()).thenReturn("phase-session-precise");

        service.ensureConversation(OWNER, WORKBENCH_ID, PHASE, 0L);

        ArgumentCaptor<ChatSession> sessionCaptor =
                ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).addSession(sessionCaptor.capture());
        ChatSession session = sessionCaptor.getValue();
        PhaseConversationProvisioning persisted =
                workbench.planConversationEnsure(PHASE, OWNER, 1L);
        assertEquals(persistedNow, session.getCreatedAt());
        assertEquals(persistedNow,
                persisted.getCurrentConversationCreatedAt());
        session.requireActiveWorkbenchPhase(
                persisted.getCurrentConversationId(),
                persisted.getAgentType(),
                persisted.getPrimaryRepositoryRoot(),
                persisted.getEnvironment(), persisted.getContextId(),
                persisted.getOwner().getOwnerId(),
                persisted.getOwner().getOwnerName(),
                persisted.getCurrentConversationCreatedAt());
    }

    @Test
    void ensureConversationShouldVerifyAndReturnExistingWithoutGeneratingOrWriting() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(PHASE, "phase-session-0", OWNER, CREATED_AT.plusSeconds(1));
        ChatSession existing = phaseSession("phase-session-0", CREATED_AT.plusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("phase-session-0")).thenReturn(existing);

        PhaseConversationResult result = service.ensureConversation(
                OWNER, WORKBENCH_ID, PHASE, 1L);

        assertFalse(result.isCreated());
        assertFalse(result.isReplayed());
        assertEquals("phase-session-0", result.getSessionId());
        assertEquals(0, result.getConversationGeneration());
        assertEquals(1L, result.getWorkbenchVersion());
        verify(sessionIdGenerator, never()).nextId();
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void ensureConversationShouldRejectForeignOwnerAndInvalidExistingSessionWithoutWrites() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(PHASE, "phase-session-0", OWNER, CREATED_AT.plusSeconds(1));
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));

        assertThrows(WorkbenchNotFoundException.class, () -> service.ensureConversation(
                FOREIGN, WORKBENCH_ID, PHASE, 1L));

        ChatSession ordinaryChat = new ChatSession(
                "phase-session-0", AgentType.CODEX, "/workspace/agent-web",
                CREATED_AT.plusSeconds(1), null);
        when(sessionRepository.findById("phase-session-0")).thenReturn(ordinaryChat);
        assertThrows(IllegalStateException.class, () -> service.ensureConversation(
                OWNER, WORKBENCH_ID, PHASE, 1L));
        verify(sessionRepository, never()).saveSession(any(ChatSession.class));
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void ensureConversationShouldDelegateAllStableSessionFactValidationToAggregate() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(PHASE, "phase-session-0", OWNER, CREATED_AT.plusSeconds(1));
        ChatSession wrongAgent = ChatSession.createWorkbenchPhase(
                "phase-session-0", AgentType.CLAUDE, "/workspace/agent-web",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", CREATED_AT.plusSeconds(1));
        wrongAgent.setEnv("local");
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("phase-session-0")).thenReturn(wrongAgent);

        assertThrows(IllegalStateException.class, () -> service.ensureConversation(
                OWNER, WORKBENCH_ID, PHASE, 1L));

        verify(sessionIdGenerator, never()).nextId();
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
    }

    @Test
    void restartConversationShouldReplayReceiptBeforeLoadingWorkbenchOrCreatingSession() {
        PhaseConversationRestartReceipt receipt = receipt();
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.of(receipt));
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WORKBENCH_ID, PHASE, "restart-key-1", 0L);

        PhaseConversationResult result = service.restartConversation(OWNER, command);

        assertTrue(result.isReplayed());
        assertFalse(result.isCreated());
        assertEquals("phase-session-1", result.getSessionId());
        assertEquals("phase-session-0", result.getPreviousSessionId());
        assertEquals(1, result.getConversationGeneration());
        assertEquals(4L, result.getWorkbenchVersion());
        verify(workbenchRepository, never()).findById(any(WorkbenchId.class));
        verify(sessionRepository, never()).findById(any(String.class));
        verify(sessionIdGenerator, never()).nextId();
    }

    @Test
    void restartConversationShouldRejectSameKeyForDifferentPhaseBeforeLoadingWorkbench() {
        PhaseConversationRestartReceipt receipt = receipt();
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.of(receipt));
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN, "restart-key-1", 3L);

        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class,
                () -> service.restartConversation(OWNER, command));

        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, error.getCode());
        verify(workbenchRepository, never()).findById(any(WorkbenchId.class));
    }

    @Test
    void restartConversationShouldRetireOldCreateNewAndPersistReceiptInOrder() {
        Workbench workbench = inProgressWorkbench(false);
        ChatSession oldSession = phaseSession("phase-session-0", CREATED_AT.plusSeconds(1));
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.empty());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("phase-session-0")).thenReturn(oldSession);
        when(sessionIdGenerator.nextId()).thenReturn("phase-session-1");
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WORKBENCH_ID, PHASE, "restart-key-1", 3L);

        PhaseConversationResult result = service.restartConversation(OWNER, command);

        assertTrue(result.isCreated());
        assertFalse(result.isReplayed());
        assertEquals("phase-session-1", result.getSessionId());
        assertEquals("phase-session-0", result.getPreviousSessionId());
        assertEquals(1, result.getConversationGeneration());
        assertEquals(4L, result.getWorkbenchVersion());
        assertEquals(NOW, oldSession.getRetiredAt());
        ArgumentCaptor<ChatSession> sessions = ArgumentCaptor.forClass(ChatSession.class);
        ArgumentCaptor<PhaseConversationRestartReceipt> receipts =
                ArgumentCaptor.forClass(PhaseConversationRestartReceipt.class);
        InOrder persisted = inOrder(sessionRepository, workbenchRepository, receiptRepository);
        persisted.verify(sessionRepository).saveSession(sessions.capture());
        persisted.verify(sessionRepository).addSession(sessions.capture());
        persisted.verify(workbenchRepository).update(workbench);
        persisted.verify(receiptRepository).add(receipts.capture());
        assertEquals(oldSession, sessions.getAllValues().get(0));
        ChatSession newSession = sessions.getAllValues().get(1);
        assertEquals(SessionKind.WORKBENCH_PHASE, newSession.getSessionKind());
        assertEquals("workbench-1:IMPLEMENT_TEST", newSession.getContextId());
        assertEquals("local", newSession.getEnv());
        assertEquals("phase-session-1", receipts.getValue().getSessionId());
    }

    @Test
    void restartConversationShouldFailBeforeCreatingSessionWhenPhaseIsNotRestartable() {
        Workbench workbench = inProgressWorkbench(true);
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.empty());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        RestartPhaseConversationCommand command = new RestartPhaseConversationCommand(
                WORKBENCH_ID, PHASE, "restart-key-1", 2L);

        WorkbenchDomainException error = assertThrows(WorkbenchDomainException.class,
                () -> service.restartConversation(OWNER, command));

        assertEquals(WorkbenchErrorCode.PHASE_RESTART_INVALID, error.getCode());
        verify(sessionIdGenerator, never()).nextId();
        verify(sessionRepository, never()).findById(any(String.class));
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(receiptRepository, never()).add(any(PhaseConversationRestartReceipt.class));
    }

    @Test
    void restartConversationShouldRejectMismatchedCurrentSessionBeforeRetirementOrCreation() {
        Workbench workbench = inProgressWorkbench(false);
        ChatSession wrongEnvironment = phaseSession(
                "phase-session-0", CREATED_AT.plusSeconds(1));
        wrongEnvironment.setEnv("prod");
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.empty());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("phase-session-0")).thenReturn(wrongEnvironment);

        assertThrows(IllegalStateException.class, () -> service.restartConversation(
                OWNER, new RestartPhaseConversationCommand(
                        WORKBENCH_ID, PHASE, "restart-key-1", 3L)));

        assertNull(wrongEnvironment.getRetiredAt());
        verify(sessionIdGenerator, never()).nextId();
        verify(sessionRepository, never()).saveSession(any(ChatSession.class));
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(receiptRepository, never()).add(any(PhaseConversationRestartReceipt.class));
    }

    @Test
    void restartConversationShouldStopPersistenceAfterOldSessionLifecycleFailure() {
        Workbench workbench = inProgressWorkbench(false);
        ChatSession oldSession = phaseSession("phase-session-0", CREATED_AT.plusSeconds(1));
        when(receiptRepository.findByOwnerAndIdempotencyKey(OWNER, "restart-key-1"))
                .thenReturn(Optional.empty());
        when(workbenchRepository.findById(WORKBENCH_ID)).thenReturn(Optional.of(workbench));
        when(sessionRepository.findById("phase-session-0")).thenReturn(oldSession);
        when(sessionIdGenerator.nextId()).thenReturn("phase-session-1");
        doThrow(new IllegalStateException("lifecycle failed"))
                .when(sessionRepository).saveSession(oldSession);

        assertThrows(IllegalStateException.class, () -> service.restartConversation(
                OWNER, new RestartPhaseConversationCommand(
                        WORKBENCH_ID, PHASE, "restart-key-1", 3L)));

        verify(sessionRepository, times(1)).saveSession(any(ChatSession.class));
        verify(sessionRepository, never()).addSession(any(ChatSession.class));
        verify(workbenchRepository, never()).update(any(Workbench.class));
        verify(receiptRepository, never()).add(any(PhaseConversationRestartReceipt.class));
    }

    private Workbench newWorkbench() {
        RepositorySelection selection = RepositorySelection.of(
                "agent-web", Collections.singletonList("agent-web"));
        RepositoryScope scope = RepositoryScope.create(
                "/workspace", selection,
                Collections.singletonList(ResolvedRepository.fromVerifiedFacts(
                        "agent-web", "/workspace/agent-web", repeat('1'), false)), 10);
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace", selection);
        return Workbench.create(
                WORKBENCH_ID, OWNER, "Phase Conversation", "独立阶段会话",
                AgentType.CODEX, "local", scope,
                new WorkspaceSnapshotReference(
                        "snapshot-1", topology.getTopologyHash(), repeat('2'), 1),
                CREATED_AT);
    }

    private Workbench inProgressWorkbench(boolean activeRun) {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(
                PHASE, "phase-session-0", OWNER, CREATED_AT.plusSeconds(1));
        workbench.prepareRun(
                PHASE, "run-1", RunMode.MODIFY_WORKSPACE, OWNER, CREATED_AT.plusSeconds(2));
        if (!activeRun) {
            workbench.finishRun(PHASE, "run-1", CREATED_AT.plusSeconds(3));
        }
        return workbench;
    }

    private ChatSession phaseSession(String sessionId, Instant createdAt) {
        ChatSession session = ChatSession.createWorkbenchPhase(
                sessionId, AgentType.CODEX, "/workspace/agent-web",
                "workbench-1:IMPLEMENT_TEST", "owner-1", "Alex", createdAt);
        session.setEnv("local");
        return session;
    }

    private PhaseConversationRestartReceipt receipt() {
        return PhaseConversationRestartReceipt.record(
                OWNER, "restart-key-1", WORKBENCH_ID, PHASE,
                "phase-session-0", "phase-session-1", 1, 4L, NOW);
    }

    private String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }
}
