package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.conversation.WorkbenchStageConversationResult;
import lombok.Getter;

/**
 * 动态 Stage Conversation 懒创建的非敏感响应投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationEnsureResponse {

    private final String sessionId;
    private final int generation;
    private final long workbenchVersion;
    private final boolean created;

    private WorkbenchStageConversationEnsureResponse(
            String sessionId, int generation,
            long workbenchVersion, boolean created) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.created = created;
    }

    public static WorkbenchStageConversationEnsureResponse from(
            WorkbenchStageConversationResult result) {
        return new WorkbenchStageConversationEnsureResponse(
                result.getSessionId(), result.getConversationGeneration(),
                result.getWorkbenchVersion(), result.isCreated());
    }
}
