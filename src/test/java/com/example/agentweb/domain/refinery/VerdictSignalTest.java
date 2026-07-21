package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link VerdictSignal#fromRaw} 单测：verdict 词汇 → 召回信号翻译。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
class VerdictSignalTest {

    @Test
    void fromRaw_should_map_positive_literals() {
        assertEquals(VerdictSignal.POSITIVE, VerdictSignal.fromRaw("结论正确"));
        assertEquals(VerdictSignal.POSITIVE, VerdictSignal.fromRaw("有帮助"));
    }

    @Test
    void fromRaw_should_map_negative_literal() {
        assertEquals(VerdictSignal.NEGATIVE, VerdictSignal.fromRaw("结论错误"));
    }

    @Test
    void fromRaw_should_map_null_and_unknown_to_none() {
        assertEquals(VerdictSignal.NONE, VerdictSignal.fromRaw(null));
        assertEquals(VerdictSignal.NONE, VerdictSignal.fromRaw(""));
        assertEquals(VerdictSignal.NONE, VerdictSignal.fromRaw("部分正确"));
    }
}
