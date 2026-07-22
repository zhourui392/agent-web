package com.example.agentweb.app.chatrun;

/**
 * Transport-neutral sink implemented by the SSE interface boundary.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunStreamSink {

    void send(ChatRunEvent event);

    void ping();

    void complete();

    void fail(Throwable error);
}
