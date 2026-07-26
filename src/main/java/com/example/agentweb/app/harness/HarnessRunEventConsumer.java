package com.example.agentweb.app.harness;

/**
 * 非阻塞事件订阅者回调，由 SSE 边界实现。
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunEventConsumer {

    void accept(HarnessRunEvent event);

    void overflow();
}