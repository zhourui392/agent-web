package com.example.agentweb.infra.agentrun;

import com.example.agentweb.domain.agentrun.AgentRuntimeAvailability;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.infra.nativeagent.NativeRuntimeRegistration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring runtime registration snapshot tests.
 *
 * @author alex
 * @since 2026-07-29
 */
class SpringAgentRuntimeRegistryTest {

    @Test
    void nativeBeanMissing_shouldExposeOnlyCliRuntimes() {
        SpringAgentRuntimeRegistry registry = new SpringAgentRuntimeRegistry(Optional.empty());

        List<AgentRuntimeAvailability> availability = registry.availability();

        assertEquals(2, availability.size());
        assertFalse(availability.stream().anyMatch(a -> a.getType() == AgentType.NATIVE));
    }

    @Test
    void nativeBeanPresent_shouldExposeBoundEnvironment() {
        SpringAgentRuntimeRegistry registry = new SpringAgentRuntimeRegistry(
                Optional.of(new NativeRuntimeRegistration("test")));

        AgentRuntimeAvailability nativeAvailability = registry.availability().stream()
                .filter(a -> a.getType() == AgentType.NATIVE)
                .findFirst().orElseThrow();

        assertTrue(nativeAvailability.supportsEnvironment("test"));
        assertFalse(nativeAvailability.supportsEnvironment("prod"));
    }

    @Test
    void nativeBeanWithMultipleEngines_shouldExposeAllBoundEnvironments() {
        SpringAgentRuntimeRegistry registry = new SpringAgentRuntimeRegistry(
                Optional.of(new NativeRuntimeRegistration(Set.of("test", "prod"))));

        AgentRuntimeAvailability nativeAvailability = registry.availability().stream()
                .filter(a -> a.getType() == AgentType.NATIVE)
                .findFirst().orElseThrow();

        assertTrue(nativeAvailability.supportsEnvironment("test"));
        assertTrue(nativeAvailability.supportsEnvironment("prod"));
        assertFalse(nativeAvailability.supportsEnvironment("staging"));
    }

    @Test
    void nativeRuntimeWithoutOperationalEvidenceCapability_shouldRemainUnavailable() {
        SpringAgentRuntimeRegistry registry = new SpringAgentRuntimeRegistry(
                Optional.of(new NativeRuntimeRegistration(Set.of("test"), Set.of())));

        assertFalse(registry.availability().stream()
                .anyMatch(availability -> availability.getType() == AgentType.NATIVE));
    }
}
