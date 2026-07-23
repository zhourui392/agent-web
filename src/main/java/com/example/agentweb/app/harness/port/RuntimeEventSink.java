package com.example.agentweb.app.harness.port;

/**
 * Runtime Adapter 归一化事件的应用入站回调。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface RuntimeEventSink {

    /**
     * 接收一条带单调序号的非敏感 Runtime 事件。
     *
     * @param event Runtime 事件
     */
    void onEvent(RuntimeEvent event);
}
