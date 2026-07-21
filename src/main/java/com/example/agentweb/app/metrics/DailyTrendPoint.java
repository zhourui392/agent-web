package com.example.agentweb.app.metrics;

import lombok.Getter;

/**
 * 单日趋势点:某日的会话数,供折线图。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Getter
public class DailyTrendPoint {

    /** UTC 日期,格式 {@code yyyy-MM-dd}。 */
    private final String date;
    private final long chatCount;

    public DailyTrendPoint(String date, long chatCount) {
        this.date = date;
        this.chatCount = chatCount;
    }
}
