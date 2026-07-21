package com.example.agentweb.domain.refinery;

/**
 * chunk 软删(归档)原因. 用于归因分析区分"被反馈否决"与"自然过期"——
 * 反例挖掘与 C1 注入归因只关心 {@link #NEGATIVE_VERDICT}.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-02
 */
public enum ArchiveReason {
    /** 飞书 verdict=结论错误, 反例隔离. */
    NEGATIVE_VERDICT,
    /** TTL 自然到期批量归档. */
    TTL_EXPIRED,
    /** 人工下线. */
    MANUAL
}
