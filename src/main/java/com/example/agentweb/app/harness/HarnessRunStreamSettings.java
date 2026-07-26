package com.example.agentweb.app.harness;

/**
 * harness run 流的技术配置。后续可接入 {@code agent.harness.stream.*} 属性。
 *
 * @author zhourui(V33215020)
 */
public interface HarnessRunStreamSettings {

    int getHeartbeatSeconds();

    int getSubscriberMaxEvents();

    int getSubscriberMaxBytes();
}