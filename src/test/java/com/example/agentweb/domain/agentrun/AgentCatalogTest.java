package com.example.agentweb.domain.agentrun;

import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 暴露策略、默认资格与运行时可用性领域测试。
 *
 * @author alex
 * @since 2026-07-29
 */
class AgentCatalogTest {

    @Test
    void nativeAvailableOnBoundEnvironment_shouldBeSelectableDiagnosisButNotDefault() {
        AgentCatalog catalog = catalogWithNative();

        AgentOffer offer = catalog.offer(AgentType.NATIVE);

        assertEquals("诊断 Agent", offer.getDisplayName());
        assertEquals(AgentPurpose.DIAGNOSIS, offer.getPurpose());
        assertTrue(offer.isUserSelectable());
        assertTrue(offer.isAvailable());
        assertTrue(offer.supportsEnvironment("test"));
        assertFalse(offer.isDefaultEligible());
        assertEquals(AgentType.NATIVE,
                catalog.resolveChatSelection(" native ", AgentType.CODEX, "test"));
    }

    @Test
    void nativeRuntimeMissing_shouldRetainDescriptionButRejectNewChat() {
        AgentCatalog catalog = new AgentCatalog(Arrays.asList(
                AgentRuntimeAvailability.availableEverywhere(AgentType.CODEX),
                AgentRuntimeAvailability.availableEverywhere(AgentType.CLAUDE)));

        AgentOffer offer = catalog.offer(AgentType.NATIVE);
        AgentRuntimeUnavailableException error = assertThrows(
                AgentRuntimeUnavailableException.class,
                () -> catalog.resolveChatSelection("NATIVE", AgentType.CODEX, "test"));

        assertFalse(offer.isAvailable());
        assertEquals("AGENT_RUNTIME_UNAVAILABLE", error.getCode());
    }

    @Test
    void nativeEnvironmentMismatch_shouldRejectWithStableCode() {
        AgentCatalog catalog = catalogWithNative();

        AgentRuntimeUnavailableException error = assertThrows(
                AgentRuntimeUnavailableException.class,
                () -> catalog.resolveChatSelection("NATIVE", AgentType.CLAUDE, "prod"));

        assertEquals("AGENT_ENV_UNAVAILABLE", error.getCode());
    }

    @Test
    void blankSelection_shouldUseOnlyDefaultEligibleAgent() {
        AgentCatalog catalog = catalogWithNative();

        assertEquals(AgentType.CLAUDE,
                catalog.resolveChatSelection("  ", AgentType.CLAUDE, "test"));
        AgentPolicyViolationException error = assertThrows(
                AgentPolicyViolationException.class,
                () -> catalog.resolveChatSelection(null, AgentType.NATIVE, "test"));
        assertEquals("AGENT_NOT_DEFAULT_ELIGIBLE", error.getCode());
    }

    @Test
    void defaultEligibleTypes_shouldExcludeNative() {
        AgentCatalog catalog = catalogWithNative();

        assertEquals(Arrays.asList(AgentType.CODEX, AgentType.CLAUDE),
                catalog.defaultEligibleTypes());
    }

    @Test
    void unknownSelection_shouldReturnStablePolicyCode() {
        AgentCatalog catalog = catalogWithNative();

        AgentPolicyViolationException error = assertThrows(
                AgentPolicyViolationException.class,
                () -> catalog.resolveChatSelection("gemini", AgentType.CODEX, "test"));

        assertEquals("UNKNOWN_AGENT_TYPE", error.getCode());
    }

    @Test
    void workbenchSurfaceShouldAllowAvailableCliAgentsAndRejectNative() {
        AgentCatalog catalog = catalogWithNative();

        assertEquals(AgentType.CODEX, catalog.requireAvailableForSurface(
                AgentType.CODEX, AgentSurface.WORKBENCH, "test"));
        assertEquals(AgentType.CLAUDE, catalog.requireAvailableForSurface(
                AgentType.CLAUDE, AgentSurface.WORKBENCH, "test"));
        AgentPolicyViolationException nativeFailure = assertThrows(
                AgentPolicyViolationException.class,
                () -> catalog.requireAvailableForSurface(
                        AgentType.NATIVE, AgentSurface.WORKBENCH, "test"));
        assertEquals("AGENT_SURFACE_UNAVAILABLE", nativeFailure.getCode());
    }

    private AgentCatalog catalogWithNative() {
        return new AgentCatalog(Arrays.asList(
                AgentRuntimeAvailability.availableEverywhere(AgentType.CODEX),
                AgentRuntimeAvailability.availableEverywhere(AgentType.CLAUDE),
                AgentRuntimeAvailability.availableOn(
                        AgentType.NATIVE, Collections.singleton("test"))));
    }
}
