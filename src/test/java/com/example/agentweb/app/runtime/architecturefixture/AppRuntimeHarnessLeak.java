package com.example.agentweb.app.runtime.architecturefixture;

import com.example.agentweb.app.harness.HarnessRunQueryService;

/**
 * ArchUnit 反例：中性 Application Runtime 不得引用 Harness Application 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class AppRuntimeHarnessLeak {

    private HarnessRunQueryService forbiddenDependency;
}
