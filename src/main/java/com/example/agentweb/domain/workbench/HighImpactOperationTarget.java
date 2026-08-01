package com.example.agentweb.domain.workbench;

import java.util.Set;

/**
 * 类型化高影响操作目标；实现必须提供稳定 Payload Hash 与状态绑定。
 *
 * @author alex
 * @since 2026-08-01
 */
public interface HighImpactOperationTarget {

    HighImpactOperationType getType();

    String requestedPayloadHash();

    String expectedStateBinding();

    Set<String> repositoryKeys();

    boolean executionPermanentlyUnavailable();
}
