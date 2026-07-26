package com.example.agentweb.app.harness;

/**
 * 传输层中立的 sink，由 SSE 接口边界实现。
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunStreamSink {

    void send(HarnessRunEvent event);

    void ping();

    void complete();

    void fail(Throwable error);
}