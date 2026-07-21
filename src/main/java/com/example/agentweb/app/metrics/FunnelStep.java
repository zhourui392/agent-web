package com.example.agentweb.app.metrics;

import lombok.Getter;

/**
 * 工单链路漏斗步骤。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-26
 */
@Getter
public class FunnelStep {

    private final String step;
    private final long count;
    private final Double conversionFromPrev;

    public FunnelStep(String step, long count, Double conversionFromPrev) {
        this.step = step;
        this.count = count;
        this.conversionFromPrev = conversionFromPrev;
    }
}
