package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * Signals that the requested replay cursor predates retained events.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public class EventCursorExpiredException extends RuntimeException {

    private final String runId;
    private final long earliestRetainedSeq;
    private final long lastEventSeq;

    public EventCursorExpiredException(String runId, long earliestRetainedSeq, long lastEventSeq) {
        super("事件回放窗口已过期，请重新加载会话消息");
        this.runId = runId;
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.lastEventSeq = lastEventSeq;
    }
}
