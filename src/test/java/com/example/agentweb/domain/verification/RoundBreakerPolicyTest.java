package com.example.agentweb.domain.verification;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证熔断策略（对齐 dianxiaoer 口径）：连续失败 3 轮上限、同因连续 2 轮即停、VERIFIED 清零。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RoundBreakerPolicyTest {

    private final RoundBreakerPolicy policy = new RoundBreakerPolicy();

    @Test
    public void empty_history_or_single_failure_should_allow_next_round() {
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(null));
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(List.of()));
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(
                rounds(VerificationOutcome.BLOCKED)));
    }

    @Test
    public void same_verdict_twice_in_a_row_should_halt() {
        VerificationHaltedException e = assertThrows(VerificationHaltedException.class,
                () -> policy.assertCanStartNextRound(
                        rounds(VerificationOutcome.BLOCKED, VerificationOutcome.BLOCKED)));
        assertTrue(e.getMessage().contains("同因"));
    }

    @Test
    public void two_different_failures_allowed_but_third_hits_round_cap() {
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(
                rounds(VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED)));

        VerificationHaltedException e = assertThrows(VerificationHaltedException.class,
                () -> policy.assertCanStartNextRound(rounds(
                        VerificationOutcome.DEPLOY_FAILED, VerificationOutcome.BLOCKED,
                        VerificationOutcome.DEPLOY_FAILED)));
        assertTrue(e.getMessage().contains("连续"));
    }

    @Test
    public void older_same_verdict_before_break_should_not_extend_streak() {
        // 从最近往回: DEPLOY_FAILED, BLOCKED —— 同因链在异 verdict 处断开, 更早的 DEPLOY_FAILED 不续接
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(rounds(
                VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED)));
    }

    @Test
    public void verified_round_should_reset_failure_counting() {
        assertDoesNotThrow(() -> policy.assertCanStartNextRound(rounds(
                VerificationOutcome.BLOCKED, VerificationOutcome.BLOCKED,
                VerificationOutcome.VERIFIED, VerificationOutcome.DEPLOY_FAILED)));
    }

    @Test
    public void custom_thresholds_should_override_defaults() {
        // 放宽同因上限到 3：默认会熔断的"连续 2 轮同 verdict"被放行
        RoundBreakerPolicy relaxed = new RoundBreakerPolicy(5, 3);
        assertDoesNotThrow(() -> relaxed.assertCanStartNextRound(
                rounds(VerificationOutcome.BLOCKED, VerificationOutcome.BLOCKED)));

        // 同因到第 3 轮才触发放宽后的上限
        VerificationHaltedException sameVerdict = assertThrows(VerificationHaltedException.class,
                () -> relaxed.assertCanStartNextRound(rounds(VerificationOutcome.BLOCKED,
                        VerificationOutcome.BLOCKED, VerificationOutcome.BLOCKED)));
        assertTrue(sameVerdict.getMessage().contains("同因"));
    }

    @Test
    public void custom_round_cap_should_apply_when_verdicts_alternate() {
        // 同因闸放到 99（实质关掉），只验轮次上限=5：交替 verdict 4 轮放行、5 轮熔断
        RoundBreakerPolicy relaxed = new RoundBreakerPolicy(5, 99);
        assertDoesNotThrow(() -> relaxed.assertCanStartNextRound(rounds(
                VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED,
                VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED)));

        VerificationHaltedException roundCap = assertThrows(VerificationHaltedException.class,
                () -> relaxed.assertCanStartNextRound(rounds(
                        VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED,
                        VerificationOutcome.BLOCKED, VerificationOutcome.DEPLOY_FAILED,
                        VerificationOutcome.BLOCKED)));
        assertTrue(roundCap.getMessage().contains("连续"));
    }

    @Test
    public void non_positive_thresholds_should_be_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new RoundBreakerPolicy(0, 2));
        assertThrows(IllegalArgumentException.class, () -> new RoundBreakerPolicy(3, 0));
    }

    @Test
    public void round_record_should_guard_required_fields() {
        assertThrows(IllegalArgumentException.class,
                () -> VerificationRound.record(" ", 1, VerificationOutcome.BLOCKED, 0, ""));
        assertThrows(IllegalArgumentException.class,
                () -> VerificationRound.record("R1", 0, VerificationOutcome.BLOCKED, 0, ""));
        assertThrows(IllegalArgumentException.class,
                () -> VerificationRound.record("R1", 1, null, 0, ""));
    }

    private List<VerificationRound> rounds(VerificationOutcome... verdicts) {
        List<VerificationRound> list = new ArrayList<>();
        for (int i = 0; i < verdicts.length; i++) {
            list.add(VerificationRound.record("R2607040001", i + 1, verdicts[i], 0, ""));
        }
        return list;
    }
}
