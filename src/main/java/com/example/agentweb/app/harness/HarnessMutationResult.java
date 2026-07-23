package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.HarnessRun;
import lombok.Getter;

/**
 * 写命令的轻量响应，详情和时间线由 CQRS QueryService 提供。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class HarnessMutationResult {

    private final String runId;
    private final String status;
    private final long version;
    private final boolean duplicated;

    private HarnessMutationResult(String runId, String status, long version, boolean duplicated) {
        this.runId = runId;
        this.status = status;
        this.version = version;
        this.duplicated = duplicated;
    }

    public static HarnessMutationResult from(HarnessRun run, boolean duplicated) {
        return new HarnessMutationResult(run.getId(), run.getStatus().name(),
                run.getVersion(), duplicated);
    }

    public static HarnessMutationResult of(String runId, String status,
                                           long version, boolean duplicated) {
        return new HarnessMutationResult(runId, status, version, duplicated);
    }
}
