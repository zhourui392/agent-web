package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.workbench.WorkbenchStatus;
import lombok.Getter;

/**
 * Admin Workbench 列表查询条件。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchListRequest {

    private final WorkbenchStatus status;
    private final AdminWorkbenchListCursor cursor;
    private final int limit;

    public AdminWorkbenchListRequest(
            WorkbenchStatus status, AdminWorkbenchListCursor cursor,
            int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "admin workbench list limit must be between 1 and 100");
        }
        this.status = status;
        this.cursor = cursor;
        this.limit = limit;
    }
}
