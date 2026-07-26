package com.example.agentweb.app.harness;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * harness run 流的技术配置，默认值硬编码。后续可接入 {@code agent.harness.stream.*} 属性。
 *
 * @author zhourui(V33215020)
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessStreamProperties implements HarnessRunStreamSettings {

    @Override
    public int getHeartbeatSeconds() {
        return 15;
    }

    @Override
    public int getSubscriberMaxEvents() {
        return 1024;
    }

    @Override
    public int getSubscriberMaxBytes() {
        return 2 * 1024 * 1024;
    }
}