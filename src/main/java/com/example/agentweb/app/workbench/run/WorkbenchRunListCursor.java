package com.example.agentweb.app.workbench.run;

import lombok.Getter;

/**
 * Workbench Run 历史的稳定游标，与 created_at DESC、run_id DESC 成对使用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkbenchRunListCursor {

    private final long createdAt;
    private final String runId;

    public WorkbenchRunListCursor(long createdAt, String runId) {
        if (createdAt < 0L) {
            throw new IllegalArgumentException(
                    "run cursor createdAt must not be negative");
        }
        if (runId == null || runId.trim().isEmpty()
                || runId.length() > 128) {
            throw new IllegalArgumentException(
                    "run cursor runId must contain 1 to 128 characters");
        }
        this.createdAt = createdAt;
        this.runId = runId;
    }
}
