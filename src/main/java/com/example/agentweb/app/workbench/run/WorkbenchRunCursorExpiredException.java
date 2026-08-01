package com.example.agentweb.app.workbench.run;

import lombok.Getter;

/**
 * Workbench Run SSE 游标早于事件保留窗口。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunCursorExpiredException
        extends RuntimeException {

    private final String runId;
    private final long earliestRetainedSeq;
    private final long lastEventSeq;

    public WorkbenchRunCursorExpiredException(
            String runId, long earliestRetainedSeq,
            long lastEventSeq, String message) {
        super(message);
        this.runId = runId;
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.lastEventSeq = lastEventSeq;
    }
}
