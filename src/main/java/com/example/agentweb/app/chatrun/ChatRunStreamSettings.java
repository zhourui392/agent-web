package com.example.agentweb.app.chatrun;

/**
 * Application-facing technical settings for resumable chat streams.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatRunStreamSettings {

    boolean isEnabled();

    int getHeartbeatSeconds();

    int getReconnectTimeoutSeconds();

    int getEventRetentionHours();

    int getRunRetentionDays();

    int getFlushIntervalMs();

    int getFlushMaxEvents();

    int getFlushMaxBytes();

    int getSubscriberMaxEvents();

    int getSubscriberMaxBytes();

    int getMaxActiveRuns();
}
