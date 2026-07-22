package com.example.agentweb.app.chatrun;

import lombok.Getter;

/**
 * Per-run diagnostic projection answering §19.3 的六问:run 是否在执行、当前最后 seq、
 * 订阅者数量、最近一次 flush 时间、终态与失败码、assistant 是否落库。
 *
 * <p>只读投影,不含消息正文;仅供管理端排障。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
public final class ChatRunDiagnosticView {

    private final String runId;
    private final String sessionId;
    private final String status;
    private final String agentType;
    /** 当前最后事件序号。 */
    private final long lastEventSeq;
    /** 已持久化事件行数。 */
    private final long eventCount;
    /** 最近一次事件落库时间 (epoch ms);无事件时为 null。 */
    private final Long lastEventAt;
    /** 当前实例上该 run 的实时订阅者数量。 */
    private final int liveSubscribers;
    /** assistant 消息 id;null 表示尚未落库。 */
    private final Long assistantMessageId;
    /** assistant 是否已落库 (= assistantMessageId 非空)。 */
    private final boolean assistantPersisted;
    private final String failureCode;
    private final String errorMessage;
    private final Integer exitCode;
    private final Long startedAt;
    private final Long finishedAt;
    private final long createdAt;

    public ChatRunDiagnosticView(String runId, String sessionId, String status, String agentType,
                                 long lastEventSeq, long eventCount, Long lastEventAt,
                                 int liveSubscribers, Long assistantMessageId, String failureCode,
                                 String errorMessage, Integer exitCode, Long startedAt,
                                 Long finishedAt, long createdAt) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.status = status;
        this.agentType = agentType;
        this.lastEventSeq = lastEventSeq;
        this.eventCount = eventCount;
        this.lastEventAt = lastEventAt;
        this.liveSubscribers = liveSubscribers;
        this.assistantMessageId = assistantMessageId;
        this.assistantPersisted = assistantMessageId != null;
        this.failureCode = failureCode;
        this.errorMessage = errorMessage;
        this.exitCode = exitCode;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdAt = createdAt;
    }
}
