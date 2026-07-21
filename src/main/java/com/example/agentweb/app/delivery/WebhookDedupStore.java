package com.example.agentweb.app.delivery;

import java.time.Instant;

/**
 * webhook 幂等去重(processed_webhook 表)。GitLab 重试带同一 X-Gitlab-Event-UUID。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public interface WebhookDedupStore {

    /**
     * 尝试标记事件已处理。
     *
     * @param eventUuid  X-Gitlab-Event-UUID
     * @param receivedAt 收到时间
     * @return true=首次处理; false=重复事件,调用方应跳过
     */
    boolean tryMarkProcessed(String eventUuid, Instant receivedAt);

    /** 删除 cutoff 之前的行(随 cleanup cron 顺带执行,防无限增长),返回删除行数 */
    int purgeBefore(Instant cutoff);
}
