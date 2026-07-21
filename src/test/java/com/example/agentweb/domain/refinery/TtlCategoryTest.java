package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author zhourui(V33215020)
 * @since 2026-05-28
 */
public class TtlCategoryTest {

    @Test
    public void from_string_should_be_case_insensitive() {
        assertEquals(TtlCategory.CODE, TtlCategory.fromString("code"));
        assertEquals(TtlCategory.CODE, TtlCategory.fromString("CODE"));
        assertEquals(TtlCategory.DEPLOY, TtlCategory.fromString(" deploy "));
        assertEquals(TtlCategory.BUSINESS, TtlCategory.fromString("Business"));
        assertEquals(TtlCategory.GENERAL, TtlCategory.fromString("general"));
    }

    @Test
    public void from_string_null_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> TtlCategory.fromString(null));
    }

    @Test
    public void from_string_unrecognized_should_throw() {
        assertThrows(IllegalArgumentException.class, () -> TtlCategory.fromString("unknown"));
        assertThrows(IllegalArgumentException.class, () -> TtlCategory.fromString(""));
    }
}
