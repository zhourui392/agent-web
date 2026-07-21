package com.example.agentweb.infra.log;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * 异步线程 MDC 上下文传播工具。
 * <p>调度到线程池/CompletableFuture 的任务在执行前丢失 MDC，必须显式快照→注入→清理。
 * 提供 {@link #wrap(Runnable)} / {@link #wrap(Callable)} 包装器与
 * {@link #newTraceIdIfAbsent()} 用于无 HTTP 上下文（定时任务、worker）的入口。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026/05/19
 */
public final class MdcContext {

    private static final int TRACE_ID_LENGTH = 8;

    private MdcContext() {
        // util
    }

    /**
     * 在异步入口生成 traceId 写入 MDC；若已有则保留。
     *
     * @return 当前 MDC 中的 traceId
     */
    public static String newTraceIdIfAbsent() {
        String existing = MDC.get(MdcFilter.MDC_TRACE_ID);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
        MDC.put(MdcFilter.MDC_TRACE_ID, traceId);
        return traceId;
    }

    /**
     * 设置会话 ID（聊天链路）。
     */
    public static void putSessionId(String sessionId) {
        if (sessionId != null && !sessionId.isEmpty()) {
            MDC.put(MdcFilter.MDC_SESSION_ID, sessionId);
        }
    }

    /**
     * 设置任务 ID（诊断链路）。
     */
    public static void putTaskId(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            MDC.put(MdcFilter.MDC_TASK_ID, taskId);
        }
    }

    /**
     * 包装 Runnable：在调用线程抓取当前 MDC 快照，执行线程恢复 → 执行 → 清理。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else {
                    MDC.clear();
                }
                task.run();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * 包装 Callable，行为同 {@link #wrap(Runnable)}。
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (snapshot != null) {
                    MDC.setContextMap(snapshot);
                } else {
                    MDC.clear();
                }
                return task.call();
            } finally {
                if (previous != null) {
                    MDC.setContextMap(previous);
                } else {
                    MDC.clear();
                }
            }
        };
    }

    /**
     * 清理本次任务范围内写入的 MDC 字段。HTTP 链路由 MdcFilter 统一兜底清理，
     * 异步任务收尾可显式调用本方法。
     */
    public static void clear() {
        MDC.clear();
    }
}
