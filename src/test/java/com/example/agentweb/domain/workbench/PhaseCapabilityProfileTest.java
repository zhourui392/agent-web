package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase Capability Profile 的引用唯一性、Hash 与 Override Policy 派生测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityProfileTest {

    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");
    private static final Instant NOW = Instant.parse("2026-08-01T16:00:00Z");

    @Test
    void createShouldCanonicalizeReferencesAndDeriveOverridePolicy() {
        PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                "review-profile", "1", WorkbenchPhase.REVIEW_REFACTOR,
                Arrays.asList(
                        optional("repository-query", PhaseCapabilityType.MCP_SERVER),
                        required("platform-safety", PhaseCapabilityType.RULE),
                        optional("refactor-assistant", PhaseCapabilityType.SKILL),
                        optional("review/style", PhaseCapabilityType.RULE)));
        PhaseCapabilityProfile reordered = PhaseCapabilityProfile.create(
                "review-profile", "1", WorkbenchPhase.REVIEW_REFACTOR,
                Arrays.asList(
                        optional("review/style", PhaseCapabilityType.RULE),
                        optional("refactor-assistant", PhaseCapabilityType.SKILL),
                        required("platform-safety", PhaseCapabilityType.RULE),
                        optional("repository-query", PhaseCapabilityType.MCP_SERVER)));

        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, profile.getPhase());
        assertEquals("platform-safety", profile.getCapabilities().get(0).getId());
        assertEquals(profile.getProfileHash(), reordered.getProfileHash());
        assertTrue(profile.getProfileHash().matches("[a-f0-9]{64}"));
        assertThrows(UnsupportedOperationException.class,
                () -> profile.getCapabilities().add(
                        optional("another", PhaseCapabilityType.SKILL)));

        CapabilityOverride allowed = CapabilityOverride.of(
                Collections.singleton("refactor-assistant"),
                Collections.<String>emptySet(),
                Collections.singleton("repository-query"),
                Collections.singleton("review/style"));
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.create(
                        WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                        profile.getProfileId(), profile.getProfileVersion(),
                        allowed, profile.getOverridePolicy(), OWNER, NOW);
        assertEquals(allowed, configuration.getOverride());
    }

    @Test
    void createShouldRejectEmptyNullAndGloballyDuplicateReferences() {
        assertThrows(IllegalArgumentException.class,
                () -> PhaseCapabilityProfile.create(
                        "profile", "1", WorkbenchPhase.SOLUTION_DESIGN,
                        Collections.<PhaseCapabilityReference>emptyList()));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseCapabilityProfile.create(
                        "profile", "1", WorkbenchPhase.SOLUTION_DESIGN,
                        Arrays.asList(
                                required("platform-safety", PhaseCapabilityType.RULE),
                                null)));
        assertThrows(IllegalArgumentException.class,
                () -> PhaseCapabilityProfile.create(
                        "profile", "1", WorkbenchPhase.SOLUTION_DESIGN,
                        Arrays.asList(
                                required("same-id", PhaseCapabilityType.RULE),
                                optional("same-id", PhaseCapabilityType.SKILL))));
    }

    @Test
    void derivedPolicyShouldRejectRequiredUntrustedAndWrongPhaseOverrides() {
        PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                "design-profile", "1", WorkbenchPhase.SOLUTION_DESIGN,
                Arrays.asList(
                        required("required-skill", PhaseCapabilityType.SKILL),
                        optional("optional-skill", PhaseCapabilityType.SKILL),
                        optional("read-query", PhaseCapabilityType.MCP_SERVER)));

        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        profile.getProfileId(), profile.getProfileVersion(),
                        CapabilityOverride.of(
                                Collections.<String>emptySet(),
                                Collections.singleton("required-skill"),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet()),
                        profile.getOverridePolicy(), OWNER, NOW));
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                        profile.getProfileId(), profile.getProfileVersion(),
                        CapabilityOverride.of(
                                Collections.singleton("unknown-skill"),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet()),
                        profile.getOverridePolicy(), OWNER, NOW));
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                        profile.getProfileId(), profile.getProfileVersion(),
                        CapabilityOverride.empty(),
                        profile.getOverridePolicy(), OWNER, NOW));
    }

    @Test
    void restoreShouldVerifyPersistedHashAgainstProfileFacts() {
        PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                "requirement-profile", "7",
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                Collections.singletonList(
                        required("platform-safety", PhaseCapabilityType.RULE)));

        PhaseCapabilityProfile restored = PhaseCapabilityProfile.restore(
                profile.getProfileId(), profile.getProfileVersion(),
                profile.getProfileHash(), profile.getPhase(),
                profile.getCapabilities());

        assertEquals(profile.getProfileHash(), restored.getProfileHash());
        assertThrows(IllegalArgumentException.class,
                () -> PhaseCapabilityProfile.restore(
                        profile.getProfileId(), profile.getProfileVersion(),
                        repeat('f'), profile.getPhase(),
                        profile.getCapabilities()));
        PhaseCapabilityProfile changed = PhaseCapabilityProfile.create(
                profile.getProfileId(), "8", profile.getPhase(),
                profile.getCapabilities());
        assertNotEquals(profile.getProfileHash(), changed.getProfileHash());
    }

    @Test
    void selectedOptionalsShouldBecomeAConstrainedExplicitOverride() {
        PhaseCapabilityProfile profile = PhaseCapabilityProfile.create(
                "implement-profile", "1", WorkbenchPhase.IMPLEMENT_TEST,
                Arrays.asList(
                        required("platform-safety", PhaseCapabilityType.RULE),
                        optional("java-tdd", PhaseCapabilityType.SKILL),
                        optional("regression-test", PhaseCapabilityType.SKILL),
                        optional("repository-query", PhaseCapabilityType.MCP_SERVER),
                        optional("local-test-runner", PhaseCapabilityType.MCP_SERVER)));

        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.<String>emptySet(),
                AdditionalCapabilityRule.create("先跑聚焦测试", 100));

        assertTrue(override.getAddedOptionalSkillIds().isEmpty());
        assertEquals(Collections.singleton("regression-test"),
                override.getRemovedOptionalSkillIds());
        assertTrue(override.hasExplicitOptionalMcpSelection());
        assertTrue(override.getSelectedOptionalMcpIds().isEmpty());
        assertEquals("先跑聚焦测试", override.getAdditionalRule().getValue());
        assertThrows(WorkbenchDomainException.class,
                () -> profile.overrideWithSelectedOptionals(
                        new HashSet<String>(Arrays.asList(
                                "java-tdd", "untrusted-local-skill")),
                        Collections.<String>emptySet(), null));
        assertThrows(WorkbenchDomainException.class,
                () -> profile.overrideWithSelectedOptionals(
                        Collections.<String>emptySet(),
                        Collections.singleton("unknown-mcp"), null));
    }

    @Test
    void referenceShouldRequireTypeAndValidatedId() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseCapabilityReference("skill", null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseCapabilityReference("  ", PhaseCapabilityType.SKILL, false));
    }

    private static PhaseCapabilityReference required(
            String id, PhaseCapabilityType type) {
        return new PhaseCapabilityReference(id, type, true);
    }

    private static PhaseCapabilityReference optional(
            String id, PhaseCapabilityType type) {
        return new PhaseCapabilityReference(id, type, false);
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }
}
