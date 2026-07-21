package com.example.agentweb.app.requirement;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 需求在跑 run 的内存计数（配额判定的数据来源，规则在 {@code RequirementQuotaPolicy}）。
 * 单实例部署（SQLite 体量）下内存计数即事实源；重启即清零属预期——进程死了 run 也死了。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementRunTracker {

    private final ConcurrentMap<String, AtomicInteger> activeRuns = new ConcurrentHashMap<>();

    public int activeCount(String requirementId) {
        AtomicInteger counter = activeRuns.get(requirementId);
        return counter == null ? 0 : counter.get();
    }

    public void increment(String requirementId) {
        activeRuns.computeIfAbsent(requirementId, key -> new AtomicInteger()).incrementAndGet();
    }

    public void decrement(String requirementId) {
        AtomicInteger counter = activeRuns.get(requirementId);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }
}
