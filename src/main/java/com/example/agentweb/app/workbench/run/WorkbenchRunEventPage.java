package com.example.agentweb.app.workbench.run;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 与 SSE 使用同一 payload 契约的持久事件分页投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunEventPage {

    private final String runId;
    private final long after;
    private final long through;
    private final long lastEventSeq;
    private final long earliestRetainedSeq;
    private final boolean hasMore;
    private final List<WorkbenchRunEvent> events;

    public WorkbenchRunEventPage(
            String runId, long after, long through, long lastEventSeq,
            long earliestRetainedSeq, boolean hasMore,
            List<WorkbenchRunEvent> events) {
        this.runId = runId;
        this.after = after;
        this.through = through;
        this.lastEventSeq = lastEventSeq;
        this.earliestRetainedSeq = earliestRetainedSeq;
        this.hasMore = hasMore;
        this.events = Collections.unmodifiableList(
                new ArrayList<WorkbenchRunEvent>(
                        Objects.requireNonNull(events, "events")));
    }
}
