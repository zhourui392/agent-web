package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chat.ChatSession;
import com.example.agentweb.domain.chat.SessionRepository;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ChatRunRepository;
import com.example.agentweb.domain.chatrun.RunSessionOriginPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Authorizes and establishes race-free SQLite replay followed by live subscription.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Service
public class ChatRunSubscriptionService {

    private final ChatRunRepository runRepository;
    private final SessionRepository sessionRepository;
    private final AuthorizedChatRunEventReplayService replayService;

    @Autowired
    public ChatRunSubscriptionService(ChatRunRepository runRepository,
                                      SessionRepository sessionRepository,
                                      AuthorizedChatRunEventReplayService replayService) {
        this.runRepository = runRepository;
        this.sessionRepository = sessionRepository;
        this.replayService = replayService;
    }

    ChatRunSubscriptionService(
            ChatRunRepository runRepository,
            SessionRepository sessionRepository,
            ChatRunEventStore eventStore,
            ChatRunEventHub eventHub,
            TaskScheduler scheduler,
            ChatRunStreamSettings settings) {
        this(runRepository, sessionRepository,
                new AuthorizedChatRunEventReplayService(
                        runRepository, eventStore, eventHub,
                        scheduler, settings));
    }

    public ChatRunStreamHandle subscribe(String runIdValue, long cursor, final ChatRunStreamSink sink) {
        final ChatRunId runId = ChatRunId.of(runIdValue);
        ChatRun authorized = requireAuthorizedRun(runId);
        return replayService.subscribe(authorized, cursor, sink);
    }

    private ChatRun requireAuthorizedRun(ChatRunId runId) {
        Optional<ChatRun> found = runRepository.findById(runId);
        if (!found.isPresent()) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
        ChatRun run = found.get();
        ChatSession session = sessionRepository.findById(run.getSessionId());
        if (session == null) {
            throw new ChatRunNotFoundException(runId.getValue());
        }
        run.requireOrdinaryChat();
        RunSessionOriginPolicy.requireCompatible(session, run);
        return run;
    }
}
