package com.example.agentweb.interfaces.architecturefixture;

import com.example.agentweb.infra.cli.CliDialect;

/**
 * ArchUnit 反例：Interface 不得引用 Provider CLI Adapter 类型。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class ProviderCliLeak {

    private CliDialect forbiddenDependency;
}
