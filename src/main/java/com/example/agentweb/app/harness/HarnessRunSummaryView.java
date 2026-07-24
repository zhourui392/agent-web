package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * Harness 管理列表轻量投影。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessRunSummaryView {

    private final String runId;
    private final String title;
    private final String status;
    private final String environment;
    private final String createdBy;
    private final long updatedAt;

    public HarnessRunSummaryView(String runId, String title, String status,
                                 String environment, String createdBy, long updatedAt) {
        this.runId = runId;
        this.title = title;
        this.status = status;
        this.environment = environment;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
    }
}
