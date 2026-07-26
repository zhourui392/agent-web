package com.example.agentweb.app.harness;

import lombok.Getter;

/**
 * Harness 管理列表轻量投影。
 *
 * <p>刻意不带 stages/artifacts 等明细：列表页的筛选与状态点只依赖 Run 级
 * {@code status}（{@code HarnessRunStatus} 已覆盖 WAITING_INPUT / WAITING_APPROVAL），
 * 需要阶段明细时走 {@code GET /runs/{runId}} 详情投影。</p>
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class HarnessRunSummaryView {

    private final String runId;
    private final String title;
    private final String status;
    private final String workingDir;
    private final String environment;
    private final String createdBy;
    private final long updatedAt;

    public HarnessRunSummaryView(String runId, String title, String status, String workingDir,
                                 String environment, String createdBy, long updatedAt) {
        this.runId = runId;
        this.title = title;
        this.status = status;
        this.workingDir = workingDir;
        this.environment = environment;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
    }
}
