package com.example.agentweb.app.metrics;

import lombok.Getter;

/**
 * 工单链路阶段时延分位。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-26
 */
@Getter
public class StageLatency {

    private final String stage;
    private final long p50Ms;
    private final long p90Ms;
    private final long p99Ms;
    private final long count;

    public StageLatency(String stage, long p50Ms, long p90Ms, long p99Ms, long count) {
        this.stage = stage;
        this.p50Ms = p50Ms;
        this.p90Ms = p90Ms;
        this.p99Ms = p99Ms;
        this.count = count;
    }
}
