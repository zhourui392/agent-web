package com.example.agentweb.domain.refinery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
class DefaultTrustTierPolicyTest {

    private final TrustTierPolicy policy = new DefaultTrustTierPolicy();

    @Test
    void chat_should_always_be_exploratory_regardless_of_verdict() {
        assertEquals(TrustTier.EXPLORATORY, policy.decide(SourceType.CHAT, VerdictSignal.NONE));
        assertEquals(TrustTier.EXPLORATORY, policy.decide(SourceType.CHAT, VerdictSignal.POSITIVE));
        assertEquals(TrustTier.EXPLORATORY, policy.decide(SourceType.CHAT, VerdictSignal.NEGATIVE));
    }

    @Test
    void diagnose_positive_should_be_verified() {
        assertEquals(TrustTier.VERIFIED, policy.decide(SourceType.DIAGNOSE, VerdictSignal.POSITIVE));
    }

    @Test
    void diagnose_none_should_be_pending() {
        assertEquals(TrustTier.PENDING, policy.decide(SourceType.DIAGNOSE, VerdictSignal.NONE));
    }

    @Test
    void diagnose_negative_should_be_filtered_via_shouldIngest() {
        assertFalse(policy.shouldIngest(SourceType.DIAGNOSE, VerdictSignal.NEGATIVE));
    }

    @Test
    void chat_negative_is_allowed_to_ingest_even_though_tier_is_low() {
        // CHAT 没有反馈闭环, NEGATIVE 在 chat 侧实际上是噪声而非"用户判错";
        // 不在入口拦截, 走 EXPLORATORY tier 让用户自己看, 防止误杀.
        assertTrue(policy.shouldIngest(SourceType.CHAT, VerdictSignal.NEGATIVE));
    }

    @Test
    void diagnose_positive_and_none_should_be_ingested() {
        assertTrue(policy.shouldIngest(SourceType.DIAGNOSE, VerdictSignal.POSITIVE));
        assertTrue(policy.shouldIngest(SourceType.DIAGNOSE, VerdictSignal.NONE));
    }
}
