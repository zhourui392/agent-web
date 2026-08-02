package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

/**
 * Admin Workbench 列表的稳定游标。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class AdminWorkbenchListCursor {

    private final long updatedAt;
    private final String workbenchId;

    public AdminWorkbenchListCursor(long updatedAt, String workbenchId) {
        if (updatedAt < 0L || workbenchId == null
                || workbenchId.trim().isEmpty()
                || workbenchId.length() > 128) {
            throw new IllegalArgumentException(
                    "admin workbench cursor is invalid");
        }
        this.updatedAt = updatedAt;
        this.workbenchId = workbenchId;
    }
}
