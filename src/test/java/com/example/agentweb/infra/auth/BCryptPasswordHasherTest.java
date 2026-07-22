package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BCryptPasswordHasher} 密码哈希适配器测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class BCryptPasswordHasherTest {

    @Test
    void encodeAndMatches_should_UseSaltedOneWayHash() {
        BCryptPasswordHasher hasher = new BCryptPasswordHasher();

        String first = hasher.encode("Test-password!2026");
        String second = hasher.encode("Test-password!2026");

        assertFalse("Test-password!2026".equals(first));
        assertFalse(first.equals(second));
        assertTrue(hasher.matches("Test-password!2026", first));
        assertFalse(hasher.matches("wrong", first));
    }
}
