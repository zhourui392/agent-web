package com.example.agentweb.app.chatrun;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * Read-only aggregate metrics for the resumable chat-run subsystem (§19.2 投影).
 *
 * <p>纯 SQLite 聚合 + 内存 EventHub gauge 组装的只读 DTO,不经聚合根。运行时直方图
 * (reconnect / replay lag / flush 耗时) 需接入独立 metrics backend 后再补,本读模型
 * 覆盖 SQL 可派生的规模、分布、终态与失败码,以及内存可用的订阅者 gauge。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
@Setter
public class ChatRunMetricsOverview {

    /** 当前活动 run 总数 (PENDING/RUNNING/CANCEL_REQUESTED)。 */
    private long activeTotal;

    /** 活动 run 按状态分布。 */
    private Map<String, Long> activeByStatus;

    /** 活动 run 按 AgentType 分布 (join chat_session)。 */
    private Map<String, Long> activeByAgentType;

    /** 全部终态 run 按终态分布 (SUCCEEDED/FAILED/CANCELLED/INTERRUPTED)。 */
    private Map<String, Long> terminalByStatus;

    /** 失败 run 按 failureCode 分布 (failure_code 非空)。 */
    private Map<String, Long> failureByCode;

    /** 已结束 run 的平均执行秒数;无样本时为 null。 */
    private Long avgDurationSeconds;

    /** 已结束 run 的最长执行秒数;无样本时为 null。 */
    private Long maxDurationSeconds;

    /** chat_run_event 持久化事件总行数。 */
    private long eventRows;

    /** chat_run_event 累计持久化 payload 字节数。 */
    private long eventPayloadBytes;

    /** 当前实例上所有 run 的实时 SSE 订阅者总数 (内存 gauge)。 */
    private int liveSubscribers;

    /** 因队列溢出被关闭的慢消费者累计次数 (内存 counter)。 */
    private long slowConsumerClosedTotal;
}
