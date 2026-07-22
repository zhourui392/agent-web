package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRunStatus;
import lombok.Getter;

/**
 * CQRS projection used to restore active runs for the current user.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ActiveChatRunView {

    private final String runId;
    private final String sessionId;
    private final ChatRunStatus status;
    private final String agentType;
    private final String workingDir;
    private final long lastEventSeq;
    private final Long startedAt;
    private final long createdAt;

    public ActiveChatRunView(String runId, String sessionId, ChatRunStatus status,
                             String agentType, String workingDir, long lastEventSeq,
                             Long startedAt, long createdAt) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.status = status;
        this.agentType = agentType;
        this.workingDir = workingDir;
        this.lastEventSeq = lastEventSeq;
        this.startedAt = startedAt;
        this.createdAt = createdAt;
    }
}
