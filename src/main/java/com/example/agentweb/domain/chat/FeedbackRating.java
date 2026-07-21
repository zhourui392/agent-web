package com.example.agentweb.domain.chat;

/**
 * 用户对一次会话中 AI 分析正确性的评价档位。
 * @author zhourui(V33215020)
 * @since 2026-05-20
 */
public enum FeedbackRating {

    /** 分析正确 */
    CORRECT,

    /** 分析部分正确 */
    PARTIALLY_CORRECT,

    /** 分析错误 */
    INCORRECT
}
