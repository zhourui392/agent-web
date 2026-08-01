package com.example.agentweb.domain.capability.architecturefixture;

import com.example.agentweb.domain.harness.HarnessRunStatus;

/**
 * ArchUnit 反例：中性 Capability Domain 不得引用 Harness Domain。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class CapabilityHarnessLeak {

    private HarnessRunStatus forbiddenDependency;
}
