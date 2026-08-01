package com.example.agentweb.app.workbench.query;

import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Workbench Owner 列表查询条件。status 为空时查询全部状态。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchListRequest {

    public static final int MAX_LIMIT = 100;

    private final WorkbenchStatus status;
    private final WorkbenchListCursor cursor;
    private final int limit;

    public WorkbenchListRequest(
            WorkbenchStatus status,
            WorkbenchListCursor cursor,
            int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        this.status = status;
        this.cursor = cursor;
        this.limit = limit;
    }
}
