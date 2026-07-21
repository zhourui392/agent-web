package com.example.agentweb.domain.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitConfigPolicy} 用户上下文判定测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
class GitConfigPolicyTest {

    private final GitConfigPolicy policy = new GitConfigPolicy();

    @Test
    void should_ReturnTrue_When_UserContextIsMissing() {
        assertTrue(policy.isSystemContext(null));
        assertTrue(policy.isSystemContext("   "));
    }

    @Test
    void should_ReturnFalse_When_UserIsLoggedIn() {
        assertFalse(policy.isSystemContext("E10001"));
    }
}
