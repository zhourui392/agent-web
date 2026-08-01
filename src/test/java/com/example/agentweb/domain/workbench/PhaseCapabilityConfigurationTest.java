package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段能力高级覆盖的安全边界、版本冲突与恢复默认语义测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    @Test
    void createAndChangeShouldKeepBaseProfileAndVersionOverrideIndependently() {
        CapabilityOverride initial = CapabilityOverride.of(
                Collections.singleton("refactor-assistant"),
                Collections.singleton("optional-linter"),
                Collections.singleton("repository-query"),
                Collections.singleton("review/explain-structure"));
        PhaseCapabilityConfiguration configuration = PhaseCapabilityConfiguration.create(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "1", initial, policy(), OWNER, NOW);

        assertEquals(0L, configuration.getVersion());
        assertEquals("review-profile", configuration.getBaseProfileId());
        assertTrue(configuration.getOverride().getAddedOptionalSkillIds()
                .contains("refactor-assistant"));
        assertTrue(configuration.getOverride().getSelectedOptionalRuleIds()
                .contains("review/explain-structure"));
        assertNull(initial.getAdditionalRule());

        CapabilityOverride explicitNoRule = CapabilityOverride.of(
                initial.getAddedOptionalSkillIds(), initial.getRemovedOptionalSkillIds(),
                initial.getSelectedOptionalMcpIds(), initial.getSelectedOptionalRuleIds(),
                null);
        assertNull(explicitNoRule.getAdditionalRule());

        configuration.changeOverride(
                0L,
                CapabilityOverride.of(
                        Collections.<String>emptySet(),
                        Collections.singleton("optional-linter"),
                        Collections.<String>emptySet(),
                        Collections.singleton("review/human-opinion-only")),
                policy(), OWNER, NOW.plusSeconds(1));

        assertEquals(1L, configuration.getVersion());
        assertEquals("1", configuration.getBaseProfileVersion());
        assertTrue(configuration.getOverride().getSelectedOptionalMcpIds().isEmpty());
        assertThrows(WorkbenchDomainException.class,
                () -> configuration.changeOverride(
                        0L, CapabilityOverride.empty(), policy(), OWNER,
                        NOW.plusSeconds(2)));
    }

    @Test
    void givenAdditionalRulesWhenCreateChangeAndRestoreThenRetainRulesAndVersionSemantics() {
        AdditionalCapabilityRule initialRule = AdditionalCapabilityRule.create(
                "初始规则\n第二行", 100);
        CapabilityOverride initial = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                initialRule);
        PhaseCapabilityConfiguration configuration = PhaseCapabilityConfiguration.create(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "1", initial, policy(), OWNER, NOW);

        assertEquals(0L, configuration.getVersion());
        assertEquals("初始规则\n第二行",
                configuration.getOverride().getAdditionalRule().getValue());

        AdditionalCapabilityRule changedRule = AdditionalCapabilityRule.create(
                "变更后规则", 100);
        CapabilityOverride changed = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                changedRule);
        configuration.changeOverride(
                0L, changed, policy(), OWNER, NOW.plusSeconds(1));

        assertEquals(1L, configuration.getVersion());
        assertEquals("变更后规则",
                configuration.getOverride().getAdditionalRule().getValue());

        AdditionalCapabilityRule restoredRule = AdditionalCapabilityRule.create(
                "持久化规则", 100);
        CapabilityOverride restoredOverride = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                restoredRule);
        PhaseCapabilityConfiguration restored = PhaseCapabilityConfiguration.restore(
                WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                "review-profile", "1", restoredOverride, OWNER,
                NOW.plusSeconds(2), 7L, policy());

        assertEquals(7L, restored.getVersion());
        assertEquals("持久化规则",
                restored.getOverride().getAdditionalRule().getValue());
    }

    @Test
    void policyShouldRejectMandatoryRemovalAndUntrustedCatalogIds() {
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1",
                        CapabilityOverride.of(
                                Collections.<String>emptySet(),
                                Collections.singleton("platform-safety"),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet()),
                        policy(), OWNER, NOW));
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1",
                        CapabilityOverride.of(
                                Collections.singleton("/tmp/untrusted-skill"),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet()),
                        policy(), OWNER, NOW));
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1",
                        CapabilityOverride.of(
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet(),
                                Collections.singleton("write-production"),
                                Collections.<String>emptySet()),
                        policy(), OWNER, NOW));
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityConfiguration.create(
                        WorkbenchId.of("workbench-1"), WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1",
                        CapabilityOverride.of(
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet(),
                                Collections.<String>emptySet(),
                                Collections.singleton("untrusted/free-form-rule")),
                        policy(), OWNER, NOW));
    }

    private static PhaseCapabilityOverridePolicy policy() {
        return PhaseCapabilityOverridePolicy.constrainedTo(
                WorkbenchPhase.REVIEW_REFACTOR,
                new HashSet<String>(Arrays.asList(
                        "refactor-assistant", "optional-linter")),
                Collections.singleton("repository-query"),
                new HashSet<String>(Arrays.asList(
                        "review/explain-structure", "review/human-opinion-only")),
                Collections.singleton("platform-safety"));
    }
}
