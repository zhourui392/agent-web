package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.conversation.PhaseConversationResult;
import lombok.Getter;

/**
 * Phase Conversation restart 的非敏感响应投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationRestartResponse {

    private final String sessionId;
    private final String previousSessionId;
    private final int generation;
    private final long workbenchVersion;
    private final boolean replayed;

    private PhaseConversationRestartResponse(
            String sessionId, String previousSessionId, int generation,
            long workbenchVersion, boolean replayed) {
        this.sessionId = sessionId;
        this.previousSessionId = previousSessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.replayed = replayed;
    }

    public static PhaseConversationRestartResponse from(
            PhaseConversationResult result) {
        return new PhaseConversationRestartResponse(
                result.getSessionId(), result.getPreviousSessionId(),
                result.getConversationGeneration(), result.getWorkbenchVersion(),
                result.isReplayed());
    }
}
