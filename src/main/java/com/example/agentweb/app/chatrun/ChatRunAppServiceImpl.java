package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
import com.example.agentweb.app.agentrun.AgentCatalogService;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.ChatRunRuntimeSelectionStore;
import com.example.agentweb.app.runtime.port.RuntimeProfileSelector;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.domain.chat.ChatMessage;
import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.ChatSessionNotFoundException;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunActivityGuard;
import com.example.agentweb.domain.chatrun.ChatRunCancellationDecision;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunSessionOriginPolicy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Transactional orchestration for run submission, status and cancellation.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class ChatRunAppServiceImpl implements ChatRunAppService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatRunAppServiceImpl.class);

    private final SessionRepository sessionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunEventStore eventStore;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunLauncher launcher;
    private final ChatRunQueryService queryService;
    private final ChatRunIdGenerator idGenerator;
    private final Clock clock;
    private final ChatRunStreamSettings settings;
    private final ChatRunActivityGuard activityGuard;
    private final ChatRunSubmissionExecutor submissionExecutor;
    private final AgentCatalogService agentCatalogService;
    private final ChatRunTerminalFinalizer terminalFinalizer;
    private final AgentExecutionGateway executionGateway;
    private final ChatRunRuntimeHandleStore handleStore;
    private final RuntimeProfileSelector profileSelector;
    private final ChatRunRuntimeSelectionStore selectionStore;

    /**
     * Source-compatible legacy construction path. The gateway is intentionally ignored;
     * Chat cancellation is owned by the common execution port.
     */
    @Deprecated
    public ChatRunAppServiceImpl(SessionRepository sessionRepository,
                                 ChatRunRepository runRepository,
                                 ChatRunEventStore eventStore,
                                 ChatRunEventAppender eventAppender,
                                 ChatRunLauncher launcher,
                                 ChatRunQueryService queryService,
                                 AgentGateway ignoredGateway,
                                 ChatRunIdGenerator idGenerator,
                                 Clock clock,
                                 ChatRunStreamSettings settings,
                                 ChatRunActivityGuard activityGuard,
                                 ChatRunSubmissionExecutor submissionExecutor,
                                 AgentCatalogService agentCatalogService,
                                 ChatRunTerminalFinalizer terminalFinalizer) {
        this(sessionRepository, runRepository, eventStore, eventAppender, launcher,
                queryService, idGenerator, clock, settings, activityGuard,
                submissionExecutor, agentCatalogService, terminalFinalizer);
    }

    public ChatRunAppServiceImpl(SessionRepository sessionRepository,
                                 ChatRunRepository runRepository,
                                 ChatRunEventStore eventStore,
                                 ChatRunEventAppender eventAppender,
                                 ChatRunLauncher launcher,
                                 ChatRunQueryService queryService,
                                 ChatRunIdGenerator idGenerator,
                                 Clock clock,
                                 ChatRunStreamSettings settings,
                                 ChatRunActivityGuard activityGuard,
                                 ChatRunSubmissionExecutor submissionExecutor,
                                 AgentCatalogService agentCatalogService,
                                 ChatRunTerminalFinalizer terminalFinalizer) {
        this(sessionRepository, runRepository, eventStore, eventAppender, launcher,
                queryService, idGenerator, clock, settings, activityGuard,
                submissionExecutor, agentCatalogService, terminalFinalizer, null, null,
                null, null);
    }

    @Autowired
    public ChatRunAppServiceImpl(SessionRepository sessionRepository,
                                 ChatRunRepository runRepository,
                                 ChatRunEventStore eventStore,
                                 ChatRunEventAppender eventAppender,
                                 ChatRunLauncher launcher,
                                 ChatRunQueryService queryService,
                                 ChatRunIdGenerator idGenerator,
                                 Clock clock,
                                 ChatRunStreamSettings settings,
                                 ChatRunActivityGuard activityGuard,
                                 ChatRunSubmissionExecutor submissionExecutor,
                                 AgentCatalogService agentCatalogService,
                                 ChatRunTerminalFinalizer terminalFinalizer,
                                 AgentExecutionGateway executionGateway,
                                 ChatRunRuntimeHandleStore handleStore,
                                 RuntimeProfileSelector profileSelector,
                                 ChatRunRuntimeSelectionStore selectionStore) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.eventStore = eventStore;
        this.eventAppender = eventAppender;
        this.launcher = launcher;
        this.queryService = queryService;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.settings = settings;
        this.activityGuard = activityGuard;
        this.submissionExecutor = submissionExecutor;
        this.agentCatalogService = agentCatalogService;
        this.terminalFinalizer = terminalFinalizer;
        this.executionGateway = executionGateway;
        this.handleStore = handleStore;
        this.profileSelector = profileSelector;
        this.selectionStore = selectionStore;
    }

    /**
     * Source-compatible legacy construction path for tests and rollback integrations. The
     * gateway is intentionally ignored; the common execution gateway remains authoritative.
     */
    @Deprecated
    public ChatRunAppServiceImpl(SessionRepository sessionRepository,
                                 ChatRunRepository runRepository,
                                 ChatRunEventStore eventStore,
                                 ChatRunEventAppender eventAppender,
                                 ChatRunLauncher launcher,
                                 ChatRunQueryService queryService,
                                 AgentGateway ignoredGateway,
                                 ChatRunIdGenerator idGenerator,
                                 Clock clock,
                                 ChatRunStreamSettings settings,
                                 ChatRunActivityGuard activityGuard,
                                 ChatRunSubmissionExecutor submissionExecutor,
                                 AgentCatalogService agentCatalogService,
                                 ChatRunTerminalFinalizer terminalFinalizer,
                                 AgentExecutionGateway executionGateway,
                                 ChatRunRuntimeHandleStore handleStore,
                                 RuntimeProfileSelector profileSelector,
                                 ChatRunRuntimeSelectionStore selectionStore) {
        this(sessionRepository, runRepository, eventStore, eventAppender, launcher,
                queryService, idGenerator, clock, settings, activityGuard,
                submissionExecutor, agentCatalogService, terminalFinalizer,
                executionGateway, handleStore, profileSelector, selectionStore);
    }

    @Override
    public ChatRunSubmission submit(SubmitChatRunCommand command) {
        return submissionExecutor.execute(() -> submitInTransaction(command));
    }

    private ChatRunSubmission submitInTransaction(SubmitChatRunCommand command) {
        ChatSession session = requireSession(command.getSessionId());
        session.requireOrdinaryChat();
        Optional<ChatRun> duplicate = runRepository.findBySessionAndIdempotencyKey(
                command.getSessionId(), command.getIdempotencyKey());
        if (duplicate.isPresent()) {
            ChatRun duplicateRun = duplicate.get();
            duplicateRun.requireOrdinaryChat();
            RunSessionOriginPolicy.requireCompatible(session, duplicateRun);
            return ChatRunSubmission.from(duplicateRun, true);
        }
        agentCatalogService.requireChatAvailable(session.getAgentType(), session.getEnv());
        int capacity = Math.max(1, settings.getMaxActiveRuns());
        if (queryService.countActiveRuns() >= capacity) {
            throw new RunCapacityExceededException(capacity);
        }
        activityGuard.requireInactive(command.getSessionId());
        Instant now = clock.instant();
        long userMessageId = sessionRepository.addMessageReturningId(command.getSessionId(),
                new ChatMessage("user", command.getMessage(), now));
        final ChatRun run = ChatRun.submit(idGenerator.nextId(), command.getSessionId(), userMessageId,
                command.getIdempotencyKey(), command.isRecallEnabled(), now);
        RuntimeSelection pendingSelection = null;
        if (profileSelector != null && profileSelector.hasProfiles()
                && selectionStore != null) {
            RuntimeSelection selection = profileSelector.selection(
                    session.getAgentType(), AgentRuntimeSurface.CHAT,
                    RunMode.DISCUSS_READ_ONLY, command.getProfileId(),
                    command.getModel(), command.getReasoningEffort());
            pendingSelection = selection;
        }
        eventAppender.appendToNewRun(run, Collections.singletonList(
                new ChatRunEventDraft("run_status", statusPayload(run))), now);
        if (pendingSelection != null) {
            selectionStore.save(run.getId(), pendingSelection);
        }
        eventAppender.afterCommit(new Runnable() {
            @Override
            public void run() {
                launcher.launch(run.getId());
            }
        });
        return ChatRunSubmission.from(run, false);
    }

    @Override
    @Transactional(readOnly = true)
    public ChatRunView find(String runId) {
        ChatRun run = requireAuthorizedRun(ChatRunId.of(runId));
        return ChatRunView.from(run, eventStore.findEarliestSequence(run.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ActiveChatRunView> findActive() {
        return queryService.findActiveForCurrentUser();
    }

    @Override
    @Transactional
    public ChatRunView stop(String runId) {
        final ChatRun run = requireAuthorizedRun(ChatRunId.of(runId));
        Instant now = clock.instant();
        ChatRunCancellationDecision decision = run.requestCancellation(now);
        if (decision.isTerminalTransition()) {
            terminalFinalizer.finalizeFirstTerminal(run, now);
        } else if (decision.isChanged()) {
            eventAppender.appendToExistingRun(run, Collections.singletonList(
                    new ChatRunEventDraft("run_status", statusPayload(run))), now);
            if (decision.isProcessStopRequired()) {
                eventAppender.afterCommit(new Runnable() {
                    @Override
                    public void run() {
                        requestRuntimeStop(run.getId());
                    }
                });
            }
        }
        return ChatRunView.from(run, eventStore.findEarliestSequence(run.getId()));
    }

    private void requestRuntimeStop(ChatRunId runId) {
        if (executionGateway == null || handleStore == null) {
            LOG.error("Common execution gateway is unavailable; cancellation remains persisted "
                    + "until runtime reconciliation, runId={}", runId.getValue());
            return;
        }
        Optional<RuntimeHandle> handle = handleStore.find(runId);
        if (handle.isPresent()) {
            executionGateway.requestStop(handle.get());
        } else {
            LOG.debug("Runtime handle is not bound yet; launcher will reconcile cancellation, "
                    + "runId={}", runId.getValue());
        }
    }

    private ChatRun requireAuthorizedRun(ChatRunId runId) {
        ChatRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ChatRunNotFoundException(runId.getValue()));
        ChatSession session = sessionRepository.findById(run.getSessionId());
        if (session == null) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
        run.requireOrdinaryChat();
        RunSessionOriginPolicy.requireCompatible(session, run);
        return run;
    }

    private ChatSession requireSession(String sessionId) {
        ChatSession session = sessionRepository.findById(sessionId);
        if (session == null) {
            throw new ChatSessionNotFoundException(sessionId);
        }
        return session;
    }

    private String statusPayload(ChatRun run) {
        return "{\"status\":\"" + run.getStatus().name() + "\"}";
    }
}
