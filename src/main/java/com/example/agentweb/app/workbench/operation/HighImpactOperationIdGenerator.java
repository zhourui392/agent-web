package com.example.agentweb.app.workbench.operation;

/**
 * 高影响操作 ID 生成端口。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationIdGenerator {

    String nextId();
}
