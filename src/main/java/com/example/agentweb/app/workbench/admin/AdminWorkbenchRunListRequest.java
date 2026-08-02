package com.example.agentweb.app.workbench.admin;

import com.example.agentweb.domain.chatrun.ChatRunStatus;
import lombok.Getter;

/**
 * Admin Workbench Run 列表查询条件。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchRunListRequest {

    private final ChatRunStatus status;
    private final AdminWorkbenchRunListCursor cursor;
    private final int limit;

    public AdminWorkbenchRunListRequest(
            ChatRunStatus status, AdminWorkbenchRunListCursor cursor,
            int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException(
                    "admin workbench run list limit must be between 1 and 100");
        }
        this.status = status;
        this.cursor = cursor;
        this.limit = limit;
    }
}
