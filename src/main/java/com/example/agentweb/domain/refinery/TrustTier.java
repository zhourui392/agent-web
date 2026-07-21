package com.example.agentweb.domain.refinery;

/**
 * 知识 chunk 的信任等级. 与 {@link SourceType} + {@link VerdictSignal} 一起决定召回 policy
 * 过滤维度: 诊断侧默认只查 {@code VERIFIED} 池, 聊天侧允许 {@code EXPLORATORY}.
 *
 * <ul>
 *   <li>{@code VERIFIED}: 已有反馈确认的结论, 可直接信赖
 *   <li>{@code PENDING}: 已落库但尚未收到反馈, 待标注
 *   <li>{@code EXPLORATORY}: 探索性结论 (例如聊天会话), 易含错, 仅供参考
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public enum TrustTier {
    VERIFIED,
    PENDING,
    EXPLORATORY
}
