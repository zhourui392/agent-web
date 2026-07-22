package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import lombok.Getter;

/**
 * Result returned by the idempotent chat run submission command.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunSubmission {

    private final String runId;
    private final String sessionId;
    private final ChatRunStatus status;
    private final long lastEventSeq;
    private final boolean duplicated;

    private ChatRunSubmission(ChatRun run, boolean duplicated) {
        this.runId = run.getId().getValue();
        this.sessionId = run.getSessionId();
        this.status = run.getStatus();
        this.lastEventSeq = run.getLastEventSeq();
        this.duplicated = duplicated;
    }

    public static ChatRunSubmission from(ChatRun run, boolean duplicated) {
        return new ChatRunSubmission(run, duplicated);
    }
}
