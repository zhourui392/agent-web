package com.example.agentweb.app.harness;

import java.util.List;

/**
 * harness 事件投影的只读查询端口。
 *
 * <p>与 chat 域不同，harness 事件由 {@code SqliteHarnessRunRepository.upsertChildren()}
 * 持久化（{@code INSERT OR IGNORE INTO harness_event}），此接口只提供读路径用于 SSE replay。</p>
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunEventStore {

    List<HarnessRunEvent> findAfterThrough(String runId, long afterExclusive,
                                           long throughInclusive, int limit);

    long findEarliestSequence(String runId);

    long findLastSequence(String runId);
}