package com.example.agentweb.infra.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MdcContext} 跨线程 MDC 传播 / 清理逻辑覆盖。
 * 每个用例前后强制 clear,避免 ThreadLocal 污染相邻测试。
 *
 * @author zhourui(V33215020)
 * @since 2026/05/27
 */
public class MdcContextTest {

    @BeforeEach
    void clearBefore() {
        MDC.clear();
    }

    @AfterEach
    void clearAfter() {
        MDC.clear();
    }

    @Test
    void newTraceIdIfAbsent_createsWhenEmpty() {
        String traceId = MdcContext.newTraceIdIfAbsent();
        assertNotNull(traceId);
        assertEquals(8, traceId.length(), "traceId 应为 8 字符短码");
        // MDC 已写入
        assertEquals(traceId, MDC.get(MdcFilter.MDC_TRACE_ID));
    }

    @Test
    void newTraceIdIfAbsent_keepsExisting() {
        MDC.put(MdcFilter.MDC_TRACE_ID, "preexist");

        String result = MdcContext.newTraceIdIfAbsent();

        assertEquals("preexist", result);
        assertEquals("preexist", MDC.get(MdcFilter.MDC_TRACE_ID));
    }

    @Test
    void newTraceIdIfAbsent_treatsEmptyAsAbsent() {
        MDC.put(MdcFilter.MDC_TRACE_ID, "");

        String result = MdcContext.newTraceIdIfAbsent();

        // 空串视作没有,重新生成
        assertNotNull(result);
        assertEquals(8, result.length());
        assertEquals(result, MDC.get(MdcFilter.MDC_TRACE_ID));
    }

    @Test
    void putSessionId_skipsNullOrEmpty() {
        MdcContext.putSessionId(null);
        assertNull(MDC.get(MdcFilter.MDC_SESSION_ID));
        MdcContext.putSessionId("");
        assertNull(MDC.get(MdcFilter.MDC_SESSION_ID));
        MdcContext.putSessionId("sess-1");
        assertEquals("sess-1", MDC.get(MdcFilter.MDC_SESSION_ID));
    }

    @Test
    void putTaskId_skipsNullOrEmpty() {
        MdcContext.putTaskId(null);
        MdcContext.putTaskId("");
        assertNull(MDC.get(MdcFilter.MDC_TASK_ID));
        MdcContext.putTaskId("task-x");
        assertEquals("task-x", MDC.get(MdcFilter.MDC_TASK_ID));
    }

    @Test
    void wrapRunnable_propagatesSnapshotToAnotherThread() throws Exception {
        MDC.put(MdcFilter.MDC_TRACE_ID, "snap-1");
        MdcContext.putSessionId("s-1");

        AtomicReference<String> seenTrace = new AtomicReference<>();
        AtomicReference<String> seenSession = new AtomicReference<>();
        Runnable wrapped = MdcContext.wrap(() -> {
            seenTrace.set(MDC.get(MdcFilter.MDC_TRACE_ID));
            seenSession.set(MDC.get(MdcFilter.MDC_SESSION_ID));
        });

        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            es.submit(wrapped).get();
        } finally {
            es.shutdown();
        }

        assertEquals("snap-1", seenTrace.get());
        assertEquals("s-1", seenSession.get());
    }

    @Test
    void wrapRunnable_restoresPreviousMdcAfterTaskOnSameThread() {
        // 主线程当前没 MDC
        Runnable wrapped = MdcContext.wrap(() -> MDC.put(MdcFilter.MDC_TASK_ID, "leak-attempt"));
        wrapped.run();
        // wrap 内 finally 把上下文还原到调用前(null → clear)
        assertNull(MDC.get(MdcFilter.MDC_TASK_ID), "wrap 不应让任务内 MDC 泄漏到调用方");
    }

    @Test
    void wrapRunnable_restoresPreviousMdcWhenCallerHasOne() {
        MDC.put(MdcFilter.MDC_TRACE_ID, "before");

        Runnable wrapped = MdcContext.wrap(() -> MDC.put(MdcFilter.MDC_TRACE_ID, "during"));
        wrapped.run();

        // 主线程 MDC 恢复到 "before",任务内 "during" 不应残留
        assertEquals("before", MDC.get(MdcFilter.MDC_TRACE_ID));
    }

    @Test
    void wrapRunnable_emptySnapshotClearsTargetThread() throws Exception {
        // 调用线程没 MDC,目标线程可能有残留 → wrap 应该 clear
        Runnable wrapped = MdcContext.wrap(() -> { /* no-op */ });

        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            // 提前污染目标线程
            es.submit(() -> MDC.put(MdcFilter.MDC_TASK_ID, "stale")).get();
            // wrap 内应 clear 后执行
            AtomicReference<String> seen = new AtomicReference<>();
            es.submit(MdcContext.wrap(() -> seen.set(MDC.get(MdcFilter.MDC_TASK_ID)))).get();
            assertNull(seen.get(), "wrap 应在目标线程清掉残留 MDC");
        } finally {
            es.shutdown();
        }
    }

    @Test
    void wrapCallable_propagatesAndReturnsResult() throws Exception {
        MDC.put(MdcFilter.MDC_TRACE_ID, "callable-trace");

        Callable<String> wrapped = MdcContext.wrap((Callable<String>) () -> MDC.get(MdcFilter.MDC_TRACE_ID));

        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            Future<String> f = es.submit(wrapped);
            assertEquals("callable-trace", f.get());
        } finally {
            es.shutdown();
        }
    }

    @Test
    void wrapCallable_restoresPreviousMdcEvenOnException() {
        MDC.put(MdcFilter.MDC_TRACE_ID, "outer");

        Callable<String> wrapped = MdcContext.wrap((Callable<String>) () -> {
            MDC.put(MdcFilter.MDC_TRACE_ID, "inner");
            throw new IllegalStateException("boom");
        });

        try {
            wrapped.call();
        } catch (Exception ignored) {
            // expected
        }

        assertEquals("outer", MDC.get(MdcFilter.MDC_TRACE_ID),
                "异常路径也应 finally 还原 MDC");
    }

    @Test
    void clear_wipesAllMdc() {
        Map<String, String> ctx = new HashMap<>();
        ctx.put(MdcFilter.MDC_TRACE_ID, "t");
        ctx.put(MdcFilter.MDC_SESSION_ID, "s");
        MDC.setContextMap(ctx);

        MdcContext.clear();

        assertNull(MDC.get(MdcFilter.MDC_TRACE_ID));
        assertNull(MDC.get(MdcFilter.MDC_SESSION_ID));
    }

    @Test
    void traceIdShortcodes_areHexLowerAlphanum() {
        // 多次生成,确认输出始终 8 字符且只含 0-9a-f
        for (int i = 0; i < 5; i++) {
            MDC.clear();
            String t = MdcContext.newTraceIdIfAbsent();
            assertEquals(8, t.length());
            assertTrue(t.matches("[0-9a-f]{8}"), "应为小写 hex 短码: " + t);
        }
    }
}
