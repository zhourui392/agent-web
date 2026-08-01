package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.RunOrigin;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 普通 Chat 首次终态没有额外聚合参与者。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class NoOpChatRunTerminalParticipant
        implements ChatRunTerminalParticipant {

    @Override
    public RunOrigin origin() {
        return RunOrigin.CHAT;
    }

    @Override
    public void onFirstTerminal(ChatRunId runId, Instant terminalAt) {
        // Ordinary Chat has no additional aggregate to finalize.
    }
}
