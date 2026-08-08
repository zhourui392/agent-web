package com.example.agentweb.app.agentrun.port;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Guards the Phase 3 removal of the legacy CLI facade and Invocation runtime entrypoint.
 *
 * @author alex
 * @since 2026-08-08
 */
class AgentRuntimeContractTest {

    @Test
    void agentRuntime_shouldNotExposeLegacyInvocationRunMethod() {
        boolean hasLegacyRun = Arrays.stream(AgentRuntime.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch("run"::equals);

        assertFalse(hasLegacyRun);
    }

    @Test
    void legacyAgentCliGateway_shouldBeRemoved() {
        boolean facadePresent;
        try {
            Class.forName("com.example.agentweb.infra.AgentCliGateway");
            facadePresent = true;
        } catch (ClassNotFoundException expected) {
            facadePresent = false;
        }

        assertFalse(facadePresent);
    }
}
