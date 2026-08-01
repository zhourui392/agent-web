package com.example.agentweb.app.runtime.architecturefixture;

import com.anthropic.agentkit.infrastructure.config.LlmProvider;

/**
 * ArchUnit 反例：Application 不得暴露 Provider SDK 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ProviderSdkLeak {

    private LlmProvider forbiddenDependency;
}
