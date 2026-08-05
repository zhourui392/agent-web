package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.conversation.WorkbenchStageSessionIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 动态 Stage Session 的服务端 UUID 生成器。
 *
 * @author alex
 * @since 2026-08-05
 */
@Component
public class UuidWorkbenchStageSessionIdGenerator
        implements WorkbenchStageSessionIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
