package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.conversation.PhaseSessionIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase Session 的服务端 UUID 生成器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class UuidPhaseSessionIdGenerator implements PhaseSessionIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
