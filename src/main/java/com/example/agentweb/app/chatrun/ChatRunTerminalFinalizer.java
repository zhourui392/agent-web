package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.runtime.port.ChatRunRuntimeHandleStore;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 原子编排 ChatRun 首次终态的来源参与者、RuntimeHandle 删除与 terminal event。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class ChatRunTerminalFinalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatRunTerminalParticipantRegistry participantRegistry;
    private final ChatRunRuntimeHandleStore handleStore;
    private final ChatRunEventAppender eventAppender;

    public ChatRunTerminalFinalizer(
            ChatRunTerminalParticipantRegistry participantRegistry,
            ChatRunRuntimeHandleStore handleStore,
            ChatRunEventAppender eventAppender) {
        this.participantRegistry = participantRegistry;
        this.handleStore = handleStore;
        this.eventAppender = eventAppender;
    }

    public void finalizeFirstTerminal(ChatRun run, Instant terminalAt) {
        if (run == null || terminalAt == null) {
            throw new IllegalArgumentException(
                    "chat run and terminal time must not be null");
        }
        run.requireTerminal();
        participantRegistry.onFirstTerminal(run, terminalAt);
        handleStore.delete(run.getId());
        eventAppender.appendToExistingRun(run, Collections.singletonList(
                new ChatRunEventDraft("terminal", terminalPayload(run))), terminalAt);
    }

    private String terminalPayload(ChatRun run) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", run.getStatus().name());
        payload.put("exitCode", run.getExitCode());
        payload.put("failureCode", run.getFailureCode());
        payload.put("errorMessage", run.getErrorMessage());
        payload.put("publicMessage", run.getErrorMessage());
        payload.put("assistantMessageId", run.getAssistantMessageId());
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "could not serialize chat run terminal event", failure);
        }
    }
}
