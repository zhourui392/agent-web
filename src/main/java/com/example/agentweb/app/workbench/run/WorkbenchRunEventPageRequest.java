package com.example.agentweb.app.workbench.run;

import lombok.Getter;

/**
 * Workbench Run 持久事件的有界分页参数。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunEventPageRequest {

    public static final int MAX_LIMIT = 500;

    private final long after;
    private final int limit;

    public WorkbenchRunEventPageRequest(long after, int limit) {
        if (after < 0L) {
            throw new IllegalArgumentException(
                    "run event cursor must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "run event limit must be between 1 and " + MAX_LIMIT);
        }
        this.after = after;
        this.limit = limit;
    }
}
