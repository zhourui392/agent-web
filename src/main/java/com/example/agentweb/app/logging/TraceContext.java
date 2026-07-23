package com.example.agentweb.app.logging;

/**
 * 后台应用流程的日志追踪上下文端口。
 *
 * @author alex
 * @since 2026-07-23
 */
public interface TraceContext {

    /**
     * Create and bind a trace identifier when the current context has none.
     *
     * @return the existing or newly created trace identifier
     */
    String newTraceIdIfAbsent();

    /**
     * Clear the trace identifier bound to the current context.
     */
    void clear();
}
