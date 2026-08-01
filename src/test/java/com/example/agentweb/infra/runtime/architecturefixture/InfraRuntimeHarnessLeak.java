package com.example.agentweb.infra.runtime.architecturefixture;

import com.example.agentweb.infra.harness.CodexHarnessRuntimeGateway;

/**
 * ArchUnit 反例：中性 Runtime Infrastructure 不得引用 Harness Adapter。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class InfraRuntimeHarnessLeak {

    private CodexHarnessRuntimeGateway forbiddenDependency;
}
