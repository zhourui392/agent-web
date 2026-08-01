package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * Workbench Run 历史列表查询条件。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunListRequest {

    public static final int MAX_LIMIT = 100;

    private final WorkbenchPhase phase;
    private final WorkbenchRunListCursor cursor;
    private final int limit;

    public WorkbenchRunListRequest(
            WorkbenchPhase phase, WorkbenchRunListCursor cursor,
            int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "run history limit must be between 1 and " + MAX_LIMIT);
        }
        this.phase = phase;
        this.cursor = cursor;
        this.limit = limit;
    }
}
