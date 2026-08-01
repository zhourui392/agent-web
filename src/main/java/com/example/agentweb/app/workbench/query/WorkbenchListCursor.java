package com.example.agentweb.app.workbench.query;

import lombok.Getter;

/**
 * Workbench 列表稳定游标，与 updated_at DESC、id DESC 排序成对使用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchListCursor {

    private static final int MAX_WORKBENCH_ID_LENGTH = 128;

    private final long updatedAt;
    private final String workbenchId;

    public WorkbenchListCursor(long updatedAt, String workbenchId) {
        if (updatedAt < 0) {
            throw new IllegalArgumentException("updatedAt must not be negative");
        }
        if (workbenchId == null || workbenchId.trim().isEmpty()
                || workbenchId.length() > MAX_WORKBENCH_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "workbenchId must contain 1 to " + MAX_WORKBENCH_ID_LENGTH + " characters");
        }
        this.updatedAt = updatedAt;
        this.workbenchId = workbenchId;
    }
}
