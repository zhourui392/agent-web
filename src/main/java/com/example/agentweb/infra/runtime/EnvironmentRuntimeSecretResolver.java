package com.example.agentweb.infra.runtime;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 从服务进程环境按显式逻辑键解析单次 MCP Secret。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public final class EnvironmentRuntimeSecretResolver
        implements RuntimeSecretResolver {

    private static final String ENVIRONMENT_REFERENCE_PREFIX = "environment:";
    private static final Pattern ENVIRONMENT_VARIABLE =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,159}");

    private final EnvironmentSource environmentSource;

    public EnvironmentRuntimeSecretResolver() {
        this(System::getenv);
    }

    EnvironmentRuntimeSecretResolver(EnvironmentSource environmentSource) {
        this.environmentSource = Objects.requireNonNull(
                environmentSource, "environmentSource");
    }

    @Override
    public char[] resolve(String reference) {
        String environmentVariable = environmentVariable(reference);
        String value = environmentSource.resolve(environmentVariable);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(
                    "required runtime Secret reference is unavailable");
        }
        return value.toCharArray();
    }

    private String environmentVariable(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException(
                    "runtime Secret reference must be an environment variable name");
        }
        String normalized = reference.trim();
        if (normalized.startsWith(ENVIRONMENT_REFERENCE_PREFIX)) {
            normalized = normalized.substring(ENVIRONMENT_REFERENCE_PREFIX.length());
        }
        if (!ENVIRONMENT_VARIABLE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "runtime Secret reference must be an environment variable name");
        }
        return normalized;
    }

    /**
     * 受控环境读取边界，仅用于隔离测试。
     */
    interface EnvironmentSource {

        String resolve(String name);
    }
}
