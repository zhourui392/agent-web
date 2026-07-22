package com.example.agentweb.app.chatrun;

/**
 * Non-blocking event subscriber callback owned by the stream boundary.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunEventConsumer {

    void accept(ChatRunEvent event);

    void overflow();
}
