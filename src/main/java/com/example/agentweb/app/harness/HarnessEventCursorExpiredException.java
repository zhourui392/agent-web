package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * 请求的 replay cursor 早于保留的事件窗口。
 *
 * @author zhourui(V33215020)
 */
@Getter
public class HarnessEventCursorExpiredException extends RuntimeException {

    private final String runId;
    private final long earliestRetainedSeq;
    private final long lastEventSeq;

    public HarnessEventCursorExpiredException(String runId, long earliestRetainedSeq, long lastEventSeq) {
        super("harness 事件回放窗口已过期，请重新加载 run");
        this.runId = runId;
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.lastEventSeq = lastEventSeq;
    }
}