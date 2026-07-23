package com.example.agentweb.config;

import com.example.agentweb.app.chatrun.ChatRunStreamSettings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Technical limits for resumable chat streaming.
 *
 * @author alex
 * @since 2026-07-22
 */
@Component
@ConfigurationProperties(prefix = "agent.chat.resumable-stream")
@Getter
@Setter
public class ResumableChatStreamProperties implements ChatRunStreamSettings {

    private int heartbeatSeconds = 15;
    private int reconnectTimeoutSeconds = 35;
    private int eventRetentionHours = 24;
    private int runRetentionDays = 30;
    private int flushIntervalMs = 100;
    private int flushMaxEvents = 32;
    private int flushMaxBytes = 65_536;
    private int subscriberMaxEvents = 1_024;
    private int subscriberMaxBytes = 2_097_152;
    private int maxActiveRuns = 8;
}
