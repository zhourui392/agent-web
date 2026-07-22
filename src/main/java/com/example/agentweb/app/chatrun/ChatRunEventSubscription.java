package com.example.agentweb.app.chatrun;

/**
 * Handle for one live run event subscription.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunEventSubscription {

    void activateAfter(long highWatermark);

    void close();
}
