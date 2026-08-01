package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.WorkbenchIdGenerator;
import com.example.agentweb.domain.workbench.WorkbenchId;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 使用完整 UUID 生成 Workbench 标识的无状态适配器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class UuidWorkbenchIdGenerator implements WorkbenchIdGenerator {

    @Override
    public WorkbenchId nextId() {
        return WorkbenchId.of(UUID.randomUUID().toString());
    }
}
