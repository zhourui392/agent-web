package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.agentrun.port.AgentGateway;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final SessionRepository sessionRepository;
    private final ChatRunRepository runRepository;
    private final ChatRunEventStore eventStore;
    private final ChatRunEventAppender eventAppender;
    private final ChatRunLauncher launcher;
    private final ChatRunQueryService queryService;
    private final AgentGateway gateway;
    private final ChatRunIdGenerator idGenerator;
    private final Clock clock;
    private final ChatRunStreamSettings settings;
    private final ChatRunActivityGuard activityGuard;
    private final ChatRunSubmissionExecutor submissionExecutor;

    public ChatRunAppServiceImpl(SessionRepository sessionRepository,
                                 ChatRunRepository runRepository,
                                 ChatRunEventStore eventStore,
                                 ChatRunEventAppender eventAppender,
                                 ChatRunLauncher launcher,
                                 ChatRunQueryService queryService,
                                 AgentGateway gateway,
                                 ChatRunIdGenerator idGenerator,
                                 Clock clock,
                                 ChatRunStreamSettings settings,
                                 ChatRunActivityGuard activityGuard,
                                 ChatRunSubmissionExecutor submissionExecutor) {
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
        this.eventStore = eventStore;
        this.eventAppender = eventAppender;
        this.launcher = launcher;
        this.queryService = queryService;
        this.gateway = gateway;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.settings = settings;
        this.activityGuard = activityGuard;
        this.submissionExecutor = submissionExecutor;
    }

    @Override
    public ChatRunSubmission submit(SubmitChatRunCommand command) {
        return submissionExecutor.execute(() -> submitInTransaction(command));
    }

    private ChatRunSubmission submitInTransaction(SubmitChatRunCommand command) {
        requireSession(command.getSessionId());
        Optional<ChatRun> duplicate = runRepository.findBySessionAndIdempotencyKey(
                command.getSessionId(), command.getIdempotencyKey());
        if (duplicate.isPresent()) {
            return ChatRunSubmission.from(duplicate.get(), true);
        }
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
        eventAppender.appendToNewRun(run, Collections.singletonList(
                new ChatRunEventDraft("run_status", statusPayload(run))), now);
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
        if (decision.isChanged()) {
            String eventType = decision.isTerminalTransition() ? "terminal" : "run_status";
            eventAppender.appendToExistingRun(run, Collections.singletonList(
                    new ChatRunEventDraft(eventType, statusPayload(run))), now);
            if (decision.isProcessStopRequired()) {
                eventAppender.afterCommit(new Runnable() {
                    @Override
                    public void run() {
                        gateway.stopStream(run.getId().getValue());
                    }
                });
            }
        }
        return ChatRunView.from(run, eventStore.findEarliestSequence(run.getId()));
    }

    private ChatRun requireAuthorizedRun(ChatRunId runId) {
        ChatRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ChatRunNotFoundException(runId.getValue()));
        if (sessionRepository.findById(run.getSessionId()) == null) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
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
