package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Immutable read model containing the persisted inputs needed by a background run.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunExecutionContext {

    private final String runId;
    private final String sessionId;
    private final long userMessageId;
    private final AgentType agentType;
    private final String workingDir;
    private final String resumeId;
    private final String env;
    private final String userId;
    private final String message;
    private final boolean recallEnabled;
    private final List<ChatRunHistoryMessageView> history;

    public ChatRunExecutionContext(String runId, String sessionId, long userMessageId,
                                   AgentType agentType, String workingDir, String resumeId,
                                   String env, String userId, String message, boolean recallEnabled,
                                   List<ChatRunHistoryMessageView> history) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.userMessageId = userMessageId;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.resumeId = resumeId;
        this.env = env;
        this.userId = userId;
        this.message = message;
        this.recallEnabled = recallEnabled;
        this.history = history == null
                ? Collections.<ChatRunHistoryMessageView>emptyList()
                : Collections.unmodifiableList(history);
    }
}
