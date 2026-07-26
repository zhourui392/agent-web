package com.example.agentweb.app.harness;

/**
 * 一次 live run 事件订阅的句柄。
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunEventSubscription {

    void activateAfter(long highWatermark);

    void close();
}