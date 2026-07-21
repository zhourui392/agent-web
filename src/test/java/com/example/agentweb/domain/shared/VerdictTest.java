package com.example.agentweb.domain.shared;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Verdict} 词汇表单测：raw 字面值解析与正/负语义判定。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
class VerdictTest {

    @Test
    void fromRaw_should_resolve_known_literals() {
        assertEquals(Verdict.CORRECT, Verdict.fromRaw("结论正确"));
        assertEquals(Verdict.HELPFUL, Verdict.fromRaw("有帮助"));
        assertEquals(Verdict.WRONG, Verdict.fromRaw("结论错误"));
    }

    @Test
    void fromRaw_should_return_unspecified_for_null_blank_or_unknown() {
        assertEquals(Verdict.UNSPECIFIED, Verdict.fromRaw(null));
        assertEquals(Verdict.UNSPECIFIED, Verdict.fromRaw(""));
        assertEquals(Verdict.UNSPECIFIED, Verdict.fromRaw("  "));
        assertEquals(Verdict.UNSPECIFIED, Verdict.fromRaw("部分正确"));
    }

    @Test
    void positive_should_cover_correct_and_helpful_only() {
        assertTrue(Verdict.CORRECT.isPositive());
        assertTrue(Verdict.HELPFUL.isPositive());
        assertFalse(Verdict.WRONG.isPositive());
        assertFalse(Verdict.UNSPECIFIED.isPositive());
    }

    @Test
    void negative_should_cover_wrong_only() {
        assertTrue(Verdict.WRONG.isNegative());
        assertFalse(Verdict.CORRECT.isNegative());
        assertFalse(Verdict.HELPFUL.isNegative());
        assertFalse(Verdict.UNSPECIFIED.isNegative());
    }

    @Test
    void raw_should_expose_canonical_literal() {
        assertEquals("结论正确", Verdict.CORRECT.raw());
        assertEquals("有帮助", Verdict.HELPFUL.raw());
        assertEquals("结论错误", Verdict.WRONG.raw());
        assertNull(Verdict.UNSPECIFIED.raw());
    }
}
