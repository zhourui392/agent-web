package com.example.agentweb.domain.verification;

import java.util.List;

/**
 * 验证熔断策略入域（M4.5，2026-07-05 纠偏）：两个阈值是服务化层的资源硬上限（防 run 空转烧钱），
 * 不复刻能力层"该验几轮"的方法论口径——该不该继续由 run 内 agent 自判，平台只兜资源上限。
 * ①连续失败轮次达上限即停 ②同因（同 verdict）重复失败即停（L1 粒度"因"以 verdict 近似，
 * L2 failed-case 签名粒度留待三端口落地）。VERIFIED 轮清零计数。阈值经构造器注入，默认 3/2。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RoundBreakerPolicy {

    /** 连续失败轮次硬上限默认值（资源兜底，非方法论口径）。 */
    private static final int DEFAULT_MAX_CONSECUTIVE_FAILED_ROUNDS = 3;

    /** 同因连续失败硬上限默认值（资源兜底，非方法论口径）。 */
    private static final int DEFAULT_MAX_SAME_VERDICT_FAILURES = 2;

    private final int maxConsecutiveFailedRounds;
    private final int maxSameVerdictFailures;

    /** 默认阈值构造器：向后兼容存量调用方（未配置时的资源兜底）。 */
    public RoundBreakerPolicy() {
        this(DEFAULT_MAX_CONSECUTIVE_FAILED_ROUNDS, DEFAULT_MAX_SAME_VERDICT_FAILURES);
    }

    /**
     * 资源熔断阈值构造器。两个阈值是服务化层的资源硬上限，随部署规模可调，
     * 不是"agent 该验几轮"的方法论口径。
     *
     * @param maxConsecutiveFailedRounds 连续失败轮次硬上限（≥1）
     * @param maxSameVerdictFailures     同因连续失败硬上限（≥1）
     */
    public RoundBreakerPolicy(int maxConsecutiveFailedRounds, int maxSameVerdictFailures) {
        if (maxConsecutiveFailedRounds < 1 || maxSameVerdictFailures < 1) {
            throw new IllegalArgumentException("round breaker thresholds must be >= 1");
        }
        this.maxConsecutiveFailedRounds = maxConsecutiveFailedRounds;
        this.maxSameVerdictFailures = maxSameVerdictFailures;
    }

    /**
     * 校验是否允许发起下一轮验证；rounds 需按 round 升序。
     *
     * @param rounds 该需求的历史轮次（可空）
     * @throws VerificationHaltedException 熔断命中
     */
    public void assertCanStartNextRound(List<VerificationRound> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return;
        }
        int consecutiveFailed = 0;
        int sameVerdictStreak = 0;
        boolean streakBroken = false;
        VerificationOutcome previousVerdict = null;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            VerificationOutcome verdict = rounds.get(i).getVerdict();
            if (verdict == VerificationOutcome.VERIFIED) {
                break;
            }
            consecutiveFailed++;
            if (previousVerdict == null) {
                previousVerdict = verdict;
                sameVerdictStreak = 1;
            } else if (!streakBroken && previousVerdict == verdict) {
                sameVerdictStreak++;
            } else {
                streakBroken = true;
            }
        }
        if (consecutiveFailed >= maxConsecutiveFailedRounds) {
            throw new VerificationHaltedException("验证熔断: 连续 " + consecutiveFailed
                    + " 轮未通过(上限 " + maxConsecutiveFailedRounds + "), 请人工介入后再发验证");
        }
        if (sameVerdictStreak >= maxSameVerdictFailures) {
            throw new VerificationHaltedException("验证熔断: 同因(" + previousVerdict + ")连续 "
                    + sameVerdictStreak + " 轮失败, 重跑不会改变结果, 请先做人工动作");
        }
    }
}
