package com.example.agentweb.domain.shared;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 公共领域文本与时间校验契约。

 * @author alex
 * @since 2026-08-01
 */
class DomainTextTest {

    @Test
    void normalizesRequiredTextAndEnforcesLength() {
        assertEquals("value", DomainText.require("  value  ", "field", 5));
        assertThrows(IllegalArgumentException.class,
                () -> DomainText.require("value!", "field", 5));
        assertThrows(IllegalArgumentException.class,
                () -> DomainText.require("  ", "field"));
    }

    @Test
    void acceptsOnlyLowercaseSha256() {
        String hash = repeat('a', 64);

        assertEquals(hash, DomainText.requireSha256(hash, "hash"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainText.requireSha256(repeat('A', 64), "hash"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainText.requireSha256("abc", "hash"));
    }

    @Test
    void requiresNonNullTimeWithoutChangingIt() {
        Instant time = Instant.parse("2026-08-01T00:00:00Z");

        assertSame(time, DomainText.requireTime(time, "time"));
        assertThrows(IllegalArgumentException.class,
                () -> DomainText.requireTime(null, "time"));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
