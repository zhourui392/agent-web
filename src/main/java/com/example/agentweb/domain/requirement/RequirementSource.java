package com.example.agentweb.domain.requirement;

/**
 * 需求来源通道。GITLAB_ISSUE（M2）由外部接入产生；TICKET_DERIVED 为历史枚举，
 * 热修派生随工单/诊断摘除已无生产者，保留仅为兼容存量数据反序列化。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum RequirementSource {

    BOARD,
    REST_API,
    GITLAB_ISSUE,
    TICKET_DERIVED
}
