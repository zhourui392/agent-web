package com.example.agentweb.infra.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runtime MCP Secret 逻辑引用的启动期环境解析测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class EnvironmentRuntimeSecretResolverTest {

    @Test
    void resolvesOnlySafeExplicitEnvironmentReferenceAsCopy() {
        EnvironmentRuntimeSecretResolver resolver =
                new EnvironmentRuntimeSecretResolver(
                        name -> "MCP_TOKEN".equals(name) ? "secret-value" : null);

        char[] first = resolver.resolve(" MCP_TOKEN ");
        first[0] = 'X';

        assertArrayEquals("secret-value".toCharArray(),
                resolver.resolve("MCP_TOKEN"));
    }

    @Test
    void resolvesPlatformEnvironmentReferenceWithoutTreatingPrefixAsVariableName() {
        // Given
        EnvironmentRuntimeSecretResolver resolver =
                new EnvironmentRuntimeSecretResolver(
                        name -> "MCP_TOKEN".equals(name) ? "secret-value" : null);

        // When
        char[] resolved = resolver.resolve("environment:MCP_TOKEN");

        // Then
        assertArrayEquals("secret-value".toCharArray(), resolved);
    }

    @Test
    void failsClosedForBlankUnsafeOrUnavailableReference() {
        EnvironmentRuntimeSecretResolver resolver =
                new EnvironmentRuntimeSecretResolver(name -> null);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(" "));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve("MCP-TOKEN"));
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve("MISSING_TOKEN"));
    }
}
