package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.RunOrigin;

import java.time.Instant;

/**
 * ChatRun 首次进入终态时，按来源执行同事务领域后果的中性扩展点。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface ChatRunTerminalParticipant {

    RunOrigin origin();

    void onFirstTerminal(ChatRunId runId, Instant terminalAt);
}
