package com.example.agentweb.app.runtime.port;

/**
 * 接收已归一化、截断并脱敏的公共 Runtime 事件。
 *
 * @author alex
 * @since 2026-08-01
 */
@FunctionalInterface
public interface RuntimeEventSink {

    void onEvent(RuntimeEvent event);
}
