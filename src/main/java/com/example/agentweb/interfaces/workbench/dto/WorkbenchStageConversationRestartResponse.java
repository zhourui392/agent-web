package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationResult;
import lombok.Getter;

/**
 * 动态 Stage Conversation 重启的非敏感响应投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationRestartResponse {

    private final String sessionId;
    private final String previousSessionId;
    private final int generation;
    private final long workbenchVersion;
    private final boolean replayed;

    private WorkbenchStageConversationRestartResponse(
            String sessionId, String previousSessionId, int generation,
            long workbenchVersion, boolean replayed) {
        this.sessionId = sessionId;
        this.previousSessionId = previousSessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.replayed = replayed;
    }

    public static WorkbenchStageConversationRestartResponse from(
            WorkbenchStageConversationResult result) {
        return new WorkbenchStageConversationRestartResponse(
                result.getSessionId(), result.getPreviousSessionId(),
                result.getConversationGeneration(), result.getWorkbenchVersion(),
                result.isReplayed());
    }
}
