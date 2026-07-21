package com.example.agentweb.domain.refinery;

/**
 * Trust tier 决策策略. RAG 子域唯一允许 "判定结论可信度" 的位置 —— 上游聚合不允许自己挑 tier.
 *
 * <p>{@link #decide} 给出 {@link TrustTier}; {@link #shouldIngest} 用于在 ingest 入口提前拦截
 * 显然不该入库的 (signal, source) 组合, 避免无谓 LLM 调用.</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public interface TrustTierPolicy {

    /**
     * 决定 chunk 的 trust tier. 调用方必须先用 {@link #shouldIngest} 过滤掉不应入库的组合.
     */
    TrustTier decide(SourceType sourceType, VerdictSignal verdict);

    /**
     * 该组合是否应该入库. 例如 DIAGNOSE + NEGATIVE → false (用户明确说错, 没必要喂 LLM).
     */
    boolean shouldIngest(SourceType sourceType, VerdictSignal verdict);
}
