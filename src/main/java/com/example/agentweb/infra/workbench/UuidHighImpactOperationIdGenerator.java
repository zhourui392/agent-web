package com.example.agentweb.infra.workbench;

import com.example.agentweb.app.workbench.operation.HighImpactOperationIdGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 使用不可预测 UUID 生成高影响操作 ID。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class UuidHighImpactOperationIdGenerator
        implements HighImpactOperationIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
