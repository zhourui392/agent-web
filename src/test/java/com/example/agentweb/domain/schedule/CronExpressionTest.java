package com.example.agentweb.domain.schedule;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author alex
 * @since 2026-07-23
 */
class CronExpressionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0 0 9 * * ?",
            "0 */30 * * * ?",
            "0 0 9 L * ?",
            "@daily"
    })
    void parse_should_accept_spring53_compatible_expression(String value) {
        assertEquals(value, CronExpression.parse(value).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "0 0 * * *",
            "0 0 25 * * ?",
            "not-a-cron"
    })
    void parse_should_reject_invalid_expression(String value) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> CronExpression.parse(value));

        assertEquals("无效的 Cron 表达式: " + value, error.getMessage());
    }
}
