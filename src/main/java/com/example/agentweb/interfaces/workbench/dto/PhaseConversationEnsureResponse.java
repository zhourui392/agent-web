package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.conversation.PhaseConversationResult;
import lombok.Getter;

/**
 * Phase Conversation 懒创建的非敏感响应投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationEnsureResponse {

    private final String sessionId;
    private final int generation;
    private final long workbenchVersion;
    private final boolean created;

    private PhaseConversationEnsureResponse(
            String sessionId, int generation,
            long workbenchVersion, boolean created) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.created = created;
    }

    public static PhaseConversationEnsureResponse from(
            PhaseConversationResult result) {
        return new PhaseConversationEnsureResponse(
                result.getSessionId(), result.getConversationGeneration(),
                result.getWorkbenchVersion(), result.isCreated());
    }
}
