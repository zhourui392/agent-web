package com.example.agentweb.domain.refinery;

/**
 * 上游聚合在 RAG 子域中的"来源类型". 决定 trust tier 默认值与召回 policy 过滤维度.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-02
 */
public enum SourceType {
    CHAT,
    DIAGNOSE,
    GENERAL
}
