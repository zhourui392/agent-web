package com.example.agentweb.domain.refinery;

import com.example.agentweb.domain.shared.Verdict;

/**
 * 上游对一次对话结论的反馈信号. 驱动 {@link TrustTier} 决策与召回降权.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public enum VerdictSignal {
    POSITIVE,
    NEGATIVE,
    NONE;

    /**
     * 按业务 verdict 原始字面值翻译为召回信号。词汇表唯一出处是 {@link Verdict}，
     * 消费方不得再复刻字符串比对。
     *
     * @param raw 原始 verdict 字面值（可为 null）
     * @return 正反馈 → POSITIVE；负反馈 → NEGATIVE；未标注/未知 → NONE
     */
    public static VerdictSignal fromRaw(String raw) {
        Verdict verdict = Verdict.fromRaw(raw);
        if (verdict.isPositive()) {
            return POSITIVE;
        }
        if (verdict.isNegative()) {
            return NEGATIVE;
        }
        return NONE;
    }
}
