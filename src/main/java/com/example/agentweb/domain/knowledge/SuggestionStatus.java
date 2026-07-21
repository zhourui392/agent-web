package com.example.agentweb.domain.knowledge;

/**
 * 知识建议收件箱状态：候选只进不自动落盘，人工审批是唯一出口（Devin 收件箱模式）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum SuggestionStatus {

    /** 待人工审批 */
    PENDING,

    /** 已批准（批准后经 issue-log 通道落盘，issueId 回填） */
    APPROVED,

    /** 已拒绝（留 rejectReason 供后续挖掘反例） */
    REJECTED
}
