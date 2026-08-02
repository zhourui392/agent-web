package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

/**
 * Admin Workbench Run 列表的稳定游标。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchRunListCursor {

    private final long createdAt;
    private final String runId;

    public AdminWorkbenchRunListCursor(long createdAt, String runId) {
        if (createdAt < 0L || runId == null
                || runId.trim().isEmpty() || runId.length() > 128) {
            throw new IllegalArgumentException(
                    "admin workbench run cursor is invalid");
        }
        this.createdAt = createdAt;
        this.runId = runId;
    }
}
