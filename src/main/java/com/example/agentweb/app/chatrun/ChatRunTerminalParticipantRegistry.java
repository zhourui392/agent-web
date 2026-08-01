package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 构造期验证全部 RunOrigin 恰好一个终态参与者，并执行中性来源分派。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class ChatRunTerminalParticipantRegistry {

    private final Map<RunOrigin, ChatRunTerminalParticipant> participants;

    public ChatRunTerminalParticipantRegistry(
            List<ChatRunTerminalParticipant> participants) {
        if (participants == null || participants.contains(null)) {
            throw new IllegalArgumentException(
                    "chat run terminal participants must not contain null");
        }
        EnumMap<RunOrigin, ChatRunTerminalParticipant> indexed =
                new EnumMap<RunOrigin, ChatRunTerminalParticipant>(RunOrigin.class);
        for (ChatRunTerminalParticipant participant : participants) {
            RunOrigin origin = participant.origin();
            if (origin == null) {
                throw new IllegalArgumentException(
                        "chat run terminal participant origin must not be null");
            }
            if (indexed.put(origin, participant) != null) {
                throw new IllegalStateException(
                        "duplicate chat run terminal participant origin: " + origin);
            }
        }
        for (RunOrigin origin : RunOrigin.values()) {
            if (!indexed.containsKey(origin)) {
                throw new IllegalStateException(
                        "missing chat run terminal participant origin: " + origin);
            }
        }
        this.participants = Collections.unmodifiableMap(indexed);
    }

    public void onFirstTerminal(ChatRun run, Instant terminalAt) {
        if (run == null || terminalAt == null) {
            throw new IllegalArgumentException(
                    "chat run and terminal time must not be null");
        }
        ChatRunTerminalParticipant participant = participants.get(run.getRunOrigin());
        if (participant == null) {
            throw new IllegalStateException(
                    "chat run terminal participant registry is incomplete");
        }
        participant.onFirstTerminal(run.getId(), terminalAt);
    }
}
