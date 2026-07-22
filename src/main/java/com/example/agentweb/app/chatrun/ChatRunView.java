package com.example.agentweb.app.chatrun;

import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunStatus;
import lombok.Getter;

/**
 * Public status projection for one authorized run.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunView {

    private final String runId;
    private final String sessionId;
    private final ChatRunStatus status;
    private final long lastEventSeq;
    private final long earliestRetainedSeq;
    private final Long startedAt;
    private final Long finishedAt;
    private final Long assistantMessageId;
    private final String failureCode;
    private final String errorMessage;

    private ChatRunView(ChatRun run, long earliestRetainedSeq) {
        this.runId = run.getId().getValue();
        this.sessionId = run.getSessionId();
        this.status = run.getStatus();
        this.lastEventSeq = run.getLastEventSeq();
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.startedAt = run.getStartedAt() == null ? null : run.getStartedAt().toEpochMilli();
        this.finishedAt = run.getFinishedAt() == null ? null : run.getFinishedAt().toEpochMilli();
        this.assistantMessageId = run.getAssistantMessageId();
        this.failureCode = run.getFailureCode();
        this.errorMessage = run.getErrorMessage();
    }

    public static ChatRunView from(ChatRun run, long earliestRetainedSeq) {
        return new ChatRunView(run, earliestRetainedSeq);
    }
}
