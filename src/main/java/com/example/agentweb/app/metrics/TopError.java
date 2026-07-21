package com.example.agentweb.app.metrics;

import lombok.Getter;

/**
 * 错误原因 Top-N。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-26
 */
@Getter
public class TopError {

    private final String error;
    private final long count;

    public TopError(String error, long count) {
        this.error = error;
        this.count = count;
    }
}
