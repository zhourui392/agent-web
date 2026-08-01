package com.example.agentweb.app.workbench.run;

import com.example.agentweb.app.chatrun.ChatRunEventAppender;
import com.example.agentweb.app.chatrun.ChatRunEventDraft;
import com.example.agentweb.app.chatrun.ChatRunLauncher;
import com.example.agentweb.app.chatrun.ChatRunQueryService;
import com.example.agentweb.app.chatrun.RunCapacityExceededException;
import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.workbench.HandoffReceptionRepository;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PromptPartSnapshot;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.domain.workbench.RuntimeEnforcementSnapshot;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchPhaseStatus;
import com.example.agentweb.domain.workbench.WorkbenchPromptHistoryDelivery;
import com.example.agentweb.domain.workbench.WorkbenchRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayload;
import com.example.agentweb.domain.workbench.WorkbenchRunPromptPayloadRepository;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshotRepository;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 已完整准备的 Workbench Run 原子提交编排测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunSubmissionCommitterTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T06:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");
    private static final WorkbenchId WORKBENCH_ID =
            WorkbenchId.of("workbench-1");
    private static final String SESSION_ID = "phase-session-1";
    private static final String RUN_ID = "workbench-run-1";
    private static final String PRIVATE_RULE_CONTENT =
            "私有 Catalog Rule 正文：禁止泄漏";

    private WorkbenchRepository workbenchRepository;
    private WorkspaceSnapshotRepository workspaceSnapshotRepository;
    private WorkbenchRunSnapshotRepository snapshotRepository;
    private WorkbenchRunPromptPayloadRepository promptRepository;
    private HandoffReceptionRepository receptionRepository;
    private SessionRepository sessionRepository;
    private ChatRunRepository runRepository;
    private ChatRunEventAppender eventAppender;
    private ChatRunLauncher launcher;
    private ChatRunActivityGuard activityGuard;
    private ChatRunQueryService runQueryService;
    private ChatRunStreamSettings streamSettings;
    private WorkbenchRunSubmissionCommitter committer;
    private Workbench workbench;
    private ChatSession session;
    private SubmitWorkbenchRunCommand command;
    private WorkspaceSnapshot workspaceSnapshot;
    private WorkbenchRunSnapshot snapshot;
    private WorkbenchRunPromptPayload promptPayload;
    private PreparedWorkbenchRun prepared;

    @BeforeEach
    void setUp() {
        workbenchRepository = mock(WorkbenchRepository.class);
        workspaceSnapshotRepository = mock(
                WorkspaceSnapshotRepository.class);
        snapshotRepository = mock(WorkbenchRunSnapshotRepository.class);
        promptRepository = mock(WorkbenchRunPromptPayloadRepository.class);
        receptionRepository = mock(HandoffReceptionRepository.class);
        sessionRepository = mock(SessionRepository.class);
        runRepository = mock(ChatRunRepository.class);
        eventAppender = mock(ChatRunEventAppender.class);
        launcher = mock(ChatRunLauncher.class);
        activityGuard = mock(ChatRunActivityGuard.class);
        runQueryService = mock(ChatRunQueryService.class);
        streamSettings = mock(ChatRunStreamSettings.class);
        WorkbenchRunSubmissionExecutor executor = action -> action.get();
        committer = new WorkbenchRunSubmissionCommitter(
                workbenchRepository, workspaceSnapshotRepository,
                snapshotRepository, promptRepository,
                receptionRepository, sessionRepository, runRepository,
                eventAppender, launcher, activityGuard, runQueryService,
                streamSettings, executor,
                Clock.fixed(NOW.plusSeconds(3), ZoneOffset.UTC));

        RepositoryScope scope = scope();
        workbench = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "实现本地工作台",
                AgentType.CODEX, "local", scope,
                snapshotReference("creation-snapshot", repeat('1')),
                NOW);
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, SESSION_ID,
                OWNER, NOW.plusSeconds(1));
        session = ChatSession.createWorkbenchPhase(
                SESSION_ID, AgentType.CODEX,
                scope.primaryRepository().getRepositoryRoot(),
                originReference(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                OWNER.getOwnerId(), OWNER.getOwnerName(),
                NOW.plusSeconds(1));
        session.setEnv("local");
        command = new SubmitWorkbenchRunCommand(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                workbench.getVersion(), "submission-key-1",
                "请核实需求边界", RunMode.DISCUSS_READ_ONLY,
                null, null, Collections.emptyList());
        promptPayload = WorkbenchRunPromptPayload.freeze(
                RUN_ID, PRIVATE_RULE_CONTENT + "\n\n请核实需求边界",
                WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                NOW.plusSeconds(2));
        workspaceSnapshot = runStartSnapshot(
                "run-start-snapshot",
                SnapshotPurpose.of("WORKBENCH_RUN_START"));
        snapshot = WorkbenchRunSnapshot.create(
                RUN_ID, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey(), command.getRequestHash(),
                RunMode.DISCUSS_READ_ONLY, scope,
                workspaceSnapshot.reference(),
                capabilityBinding(), null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        CanonicalHashing.sha256(command.getMessage()),
                        command.getMessage().length())),
                promptPayload.getPromptHash(),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", scope.getScopeHash(),
                        scope.getPrimaryRepositoryKey(), 1800L, 8388608L),
                null, NOW.plusSeconds(2));
        prepared = PreparedWorkbenchRun.of(
                command, snapshot, workspaceSnapshot,
                promptPayload, null, null);

        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(workbench));
        when(snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey())).thenReturn(Optional.empty());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(session);
        when(sessionRepository.addMessageReturningId(any(), any()))
                .thenReturn(41L);
        when(runQueryService.countActiveRuns()).thenReturn(0L);
        when(streamSettings.getMaxActiveRuns()).thenReturn(4);
    }

    @Test
    void submitShouldPersistSixFactsBeforeRegisteringAfterCommitLaunch()
            throws Exception {
        WorkbenchRunSubmissionResult result =
                committer.commit(OWNER, prepared);

        assertEquals(RUN_ID, result.getRunId());
        assertEquals(SESSION_ID, result.getSessionId());
        assertEquals(ChatRunStatus.PENDING, result.getStatus());
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS,
                result.getPhaseStatus());
        assertEquals(2L, result.getWorkbenchVersion());
        assertEquals(snapshot.getCapabilityBinding().getBindingHash(),
                result.getCapabilitySnapshotHash());
        assertEquals(snapshot.getRepositoryScopeHash(),
                result.getRepositoryScopeHash());
        assertFalse(result.isReplayed());
        assertEquals(RUN_ID, workbench.getActiveWriteRunReference() == null
                ? workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .getActiveRunReference().getRunId()
                : null);
        assertNull(workbench.getActiveWriteRunReference());

        ArgumentCaptor<ChatMessage> message =
                ArgumentCaptor.forClass(ChatMessage.class);
        verify(sessionRepository).addMessageReturningId(
                org.mockito.ArgumentMatchers.eq(SESSION_ID),
                message.capture());
        assertEquals("user", message.getValue().getRole());
        assertEquals(command.getMessage(), message.getValue().getContent());
        assertEquals(NOW.plusSeconds(3), message.getValue().getTimestamp());

        ArgumentCaptor<ChatRun> run =
                ArgumentCaptor.forClass(ChatRun.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<ChatRunEventDraft>> events =
                ArgumentCaptor.forClass(java.util.List.class);
        verify(eventAppender).appendToNewRun(
                run.capture(), events.capture(),
                org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(3)));
        assertEquals(ChatRunId.of(RUN_ID), run.getValue().getId());
        assertEquals(SESSION_ID, run.getValue().getSessionId());
        assertEquals(RunOrigin.WORKBENCH, run.getValue().getRunOrigin());
        assertEquals(ExecutionContextReference.of(
                        originReference(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                        RUN_ID),
                run.getValue().getExecutionContextReference());
        assertFalse(run.getValue().isRecallEnabled());
        assertEquals(1, events.getValue().size());
        assertEquals("run_status", events.getValue().get(0).getEventType());
        JsonNode payload = new ObjectMapper().readTree(
                events.getValue().get(0).getPayload());
        assertEquals("workbench-run-event@1",
                payload.path("schemaVersion").asText());
        assertEquals(RUN_ID, payload.path("runId").asText());
        assertEquals(WORKBENCH_ID.getValue(),
                payload.path("workbenchId").asText());
        assertEquals("REQUIREMENT_ANALYSIS", payload.path("phase").asText());
        assertEquals(NOW.plusSeconds(3).toEpochMilli(),
                payload.path("occurredAt").asLong());
        assertEquals("PENDING", payload.path("data").path("status").asText());
        assertFalse(events.getValue().get(0).getPayload()
                .contains(PRIVATE_RULE_CONTENT));

        InOrder order = inOrder(
                eventAppender, workspaceSnapshotRepository,
                snapshotRepository, promptRepository,
                workbenchRepository);
        order.verify(eventAppender).appendToNewRun(any(), any(), any());
        order.verify(workspaceSnapshotRepository).add(workspaceSnapshot);
        order.verify(snapshotRepository).add(snapshot);
        order.verify(promptRepository).add(promptPayload);
        order.verify(workbenchRepository).update(workbench);
        ArgumentCaptor<Runnable> launch =
                ArgumentCaptor.forClass(Runnable.class);
        order.verify(eventAppender).afterCommit(launch.capture());
        verifyNoInteractions(launcher);

        launch.getValue().run();

        verify(launcher).launch(ChatRunId.of(RUN_ID));
        verify(receptionRepository, never()).save(any());
    }

    @Test
    void exactReplayShouldReturnBoundRunWithoutRepeatingAnyWriteOrLaunch() {
        ChatRun existingRun = ChatRun.submit(
                ChatRunId.of(RUN_ID), SESSION_ID, 41L,
                command.getIdempotencyKey(), false,
                RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        originReference(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                        RUN_ID), NOW.plusSeconds(3));
        when(snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey())).thenReturn(Optional.of(snapshot));
        when(runRepository.findById(ChatRunId.of(RUN_ID)))
                .thenReturn(Optional.of(existingRun));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(promptPayload));

        WorkbenchRunSubmissionResult result =
                committer.commit(OWNER, prepared);

        assertTrue(result.isReplayed());
        assertEquals(RUN_ID, result.getRunId());
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never())
                .appendToNewRun(any(), any(), any());
        verify(workspaceSnapshotRepository, never()).add(any());
        verify(snapshotRepository, never()).add(any());
        verify(promptRepository, never()).add(any());
        verify(workbenchRepository, never()).update(any());
        verify(eventAppender, never()).afterCommit(any());
        verifyNoInteractions(launcher);
    }

    @Test
    void fastReplayShouldUseOwnerScopedSnapshotBeforePreparationFactsExist() {
        SubmitWorkbenchRunCommand staleVersionRetry =
                new SubmitWorkbenchRunCommand(
                        WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        0L, command.getIdempotencyKey(),
                        command.getMessage(), command.getRunMode(),
                        null, null, Collections.emptyList());
        assertEquals(command.getRequestHash(),
                staleVersionRetry.getRequestHash());
        ChatRun existingRun = ChatRun.submit(
                ChatRunId.of(RUN_ID), SESSION_ID, 41L,
                command.getIdempotencyKey(), false,
                RunOrigin.WORKBENCH,
                ExecutionContextReference.of(
                        originReference(WorkbenchPhase.REQUIREMENT_ANALYSIS),
                        RUN_ID), NOW.plusSeconds(3));
        when(snapshotRepository.findReplayCandidate(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey()))
                .thenReturn(Optional.of(snapshot));
        when(runRepository.findById(ChatRunId.of(RUN_ID)))
                .thenReturn(Optional.of(existingRun));
        when(promptRepository.findByRunId(RUN_ID))
                .thenReturn(Optional.of(promptPayload));

        Optional<WorkbenchRunSubmissionResult> result =
                committer.replayIfPresent(OWNER, staleVersionRetry);

        assertTrue(result.isPresent());
        assertTrue(result.get().isReplayed());
        assertEquals(RUN_ID, result.get().getRunId());
        verify(snapshotRepository).findReplayCandidate(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey());
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never()).afterCommit(any());
        verifyNoInteractions(launcher);
    }

    @Test
    void fastReplayShouldStopAfterOwnerScopedLookupWhenNoCandidateExists() {
        when(snapshotRepository.findReplayCandidate(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey()))
                .thenReturn(Optional.empty());

        Optional<WorkbenchRunSubmissionResult> result =
                committer.replayIfPresent(OWNER, command);

        assertFalse(result.isPresent());
        verifyNoInteractions(workbenchRepository, promptRepository,
                runRepository);
    }

    @Test
    void fastReplayShouldRejectReusedKeyWithDifferentCanonicalRequestHash() {
        SubmitWorkbenchRunCommand conflictingCommand =
                new SubmitWorkbenchRunCommand(
                        WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        command.getExpectedVersion(),
                        command.getIdempotencyKey(),
                        "不同问题", RunMode.DISCUSS_READ_ONLY,
                        null, null, Collections.emptyList());
        when(snapshotRepository.findReplayCandidate(
                OWNER, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey()))
                .thenReturn(Optional.of(snapshot));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> committer.replayIfPresent(
                        OWNER, conflictingCommand));

        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                failure.getCode());
        verifyNoInteractions(promptRepository, runRepository);
    }

    @Test
    void capacityFullShouldFailBeforeAggregateMutationOrPersistence() {
        when(runQueryService.countActiveRuns()).thenReturn(4L);
        when(streamSettings.getMaxActiveRuns()).thenReturn(4);

        assertThrows(RunCapacityExceededException.class,
                () -> committer.commit(OWNER, prepared));

        assertNull(workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .getActiveRunReference());
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never())
                .appendToNewRun(any(), any(), any());
        verify(workspaceSnapshotRepository, never()).add(any());
        verify(snapshotRepository, never()).add(any());
        verify(promptRepository, never()).add(any());
        verify(workbenchRepository, never()).update(any());
    }

    @Test
    void reusedIdempotencyKeyWithDifferentRequestShouldFailBeforeRunLookupOrWrite() {
        SubmitWorkbenchRunCommand conflictingCommand =
                new SubmitWorkbenchRunCommand(
                        WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        command.getExpectedVersion(),
                        command.getIdempotencyKey(),
                        "不同问题", RunMode.DISCUSS_READ_ONLY,
                        null, null, Collections.emptyList());
        WorkbenchRunPromptPayload conflictingPrompt =
                WorkbenchRunPromptPayload.freeze(
                        "conflicting-run", "不同 Prompt",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX,
                        NOW.plusSeconds(2));
        WorkbenchRunSnapshot conflictingSnapshot =
                snapshotFor(conflictingCommand, conflictingPrompt,
                        "conflicting-run",
                        runStartSnapshot(
                                "run-start-conflicting-run",
                                SnapshotPurpose.of("WORKBENCH_RUN_START")));
        WorkspaceSnapshot conflictingWorkspaceSnapshot = runStartSnapshot(
                "run-start-conflicting-run",
                SnapshotPurpose.of("WORKBENCH_RUN_START"));
        PreparedWorkbenchRun conflictingPrepared =
                PreparedWorkbenchRun.of(
                        conflictingCommand, conflictingSnapshot,
                        conflictingWorkspaceSnapshot,
                        conflictingPrompt, null, null);
        when(snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                command.getIdempotencyKey())).thenReturn(Optional.of(snapshot));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> committer.commit(OWNER, conflictingPrepared));

        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                failure.getCode());
        verifyNoInteractions(runRepository);
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never())
                .appendToNewRun(any(), any(), any());
        verify(workspaceSnapshotRepository, never()).add(any());
        verify(snapshotRepository, never()).add(any());
        verify(promptRepository, never()).add(any());
        verify(workbenchRepository, never()).update(any());
    }

    @Test
    void phaseWithoutConversationShouldFailBeforeMessageAndRunPersistence() {
        Workbench noConversation = Workbench.create(
                WORKBENCH_ID, OWNER, "Workbench", "实现本地工作台",
                AgentType.CODEX, "local", scope(),
                snapshotReference("creation-snapshot", repeat('1')),
                NOW);
        SubmitWorkbenchRunCommand noConversationCommand =
                new SubmitWorkbenchRunCommand(
                        WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        noConversation.getVersion(),
                        command.getIdempotencyKey(), command.getMessage(),
                        command.getRunMode(), null, null,
                        Collections.emptyList());
        PreparedWorkbenchRun noConversationPrepared =
                PreparedWorkbenchRun.of(
                        noConversationCommand, snapshot,
                        workspaceSnapshot,
                        promptPayload, null, null);
        when(workbenchRepository.findById(WORKBENCH_ID))
                .thenReturn(Optional.of(noConversation));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> committer.commit(OWNER, noConversationPrepared));

        assertEquals(WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                failure.getCode());
        verify(sessionRepository, never())
                .addMessageReturningId(any(), any());
        verify(eventAppender, never())
                .appendToNewRun(any(), any(), any());
        verify(workspaceSnapshotRepository, never()).add(any());
    }

    @Test
    void preparedCandidateShouldRejectMismatchedWorkspaceSnapshot() {
        WorkspaceSnapshot different = runStartSnapshot(
                "different-run-start",
                SnapshotPurpose.of("WORKBENCH_RUN_START"));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> PreparedWorkbenchRun.of(
                        command, snapshot, different,
                        promptPayload, null, null));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
    }

    @Test
    void preparedCandidateShouldRejectNonRunStartWorkspaceSnapshot() {
        WorkspaceSnapshot wrongPurpose = runStartSnapshot(
                workspaceSnapshot.getSnapshotId(),
                SnapshotPurpose.of("WORKBENCH_CREATE"));

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> PreparedWorkbenchRun.of(
                        command, snapshot, wrongPurpose,
                        promptPayload, null, null));

        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                failure.getCode());
    }

    private WorkbenchRunSnapshot snapshotFor(
            SubmitWorkbenchRunCommand source,
            WorkbenchRunPromptPayload prompt, String runId,
            WorkspaceSnapshot candidateWorkspaceSnapshot) {
        RepositoryScope scope = workbench.getRepositoryScope();
        return WorkbenchRunSnapshot.create(
                runId, WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                source.getIdempotencyKey(), source.getRequestHash(),
                RunMode.DISCUSS_READ_ONLY, scope,
                candidateWorkspaceSnapshot.reference(),
                capabilityBinding(), null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "owner",
                        CanonicalHashing.sha256(source.getMessage()),
                        source.getMessage().length())),
                prompt.getPromptHash(),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42.0", scope.getScopeHash(),
                        scope.getPrimaryRepositoryKey(), 1800L, 8388608L),
                null, NOW.plusSeconds(2));
    }

    private static WorkspaceSnapshot runStartSnapshot(
            String snapshotId, SnapshotPurpose purpose) {
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace",
                RepositorySelection.of(
                        "agent-web",
                        Collections.singletonList("agent-web")));
        RepositoryBaseline baseline = RepositoryBaseline.capture(
                "agent-web", "/workspace/agent-web", "master",
                repeat('d', 40), true, repeat('e'), NOW.plusSeconds(2));
        return WorkspaceSnapshot.capture(
                snapshotId, purpose, topology,
                Collections.singletonList(baseline),
                Collections.emptyList(), NOW.plusSeconds(1),
                NOW.plusSeconds(2));
    }

    private static RepositoryScope scope() {
        return RepositoryScope.create(
                "/workspace",
                RepositorySelection.of(
                        "agent-web",
                        Collections.singletonList("agent-web")),
                Collections.singletonList(
                        ResolvedRepository.fromVerifiedFacts(
                                "agent-web", "/workspace/agent-web",
                                repeat('a'), false)),
                8);
    }

    private static WorkspaceSnapshotReference snapshotReference(
            String id, String stateHash) {
        WorkspaceTopology topology = WorkspaceTopology.of(
                "/workspace",
                RepositorySelection.of(
                        "agent-web",
                        Collections.singletonList("agent-web")));
        return new WorkspaceSnapshotReference(
                id, topology.getTopologyHash(), stateHash, 1);
    }

    private static ResolvedCapabilityBinding capabilityBinding() {
        return ResolvedCapabilityBinding.resolve(
                "policy@1", "requirement-profile", "1", repeat('b'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "PLATFORM", repeat('c'),
                        true, "强制安全规则")),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), "CODEX_WORKBENCH@1");
    }

    private static String originReference(WorkbenchPhase phase) {
        return WORKBENCH_ID.getValue() + ":" + phase.name();
    }

    private static String repeat(char value) {
        return String.join("", Collections.nCopies(
                64, String.valueOf(value)));
    }

    private static String repeat(char value, int count) {
        return String.join("", Collections.nCopies(
                count, String.valueOf(value)));
    }
}
