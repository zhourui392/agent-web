package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.HarnessIdGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Harness UUID 标识生成器。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class UuidHarnessIdGenerator implements HarnessIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
