package com.example.agentweb.domain.workbench;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertEquals(1L, configuration.getVersion());
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
                1L,
                CapabilityOverride.of(
                        Collections.<String>emptySet(),
                        Collections.singleton("optional-linter"),
                        Collections.<String>emptySet(),
                        Collections.singleton("review/human-opinion-only")),
                policy(), OWNER, NOW.plusSeconds(1));

        assertEquals(2L, configuration.getVersion());
        assertEquals("1", configuration.getBaseProfileVersion());
        assertTrue(configuration.getOverride().getSelectedOptionalMcpIds().isEmpty());
        assertThrows(WorkbenchDomainException.class,
                () -> configuration.changeOverride(
                        1L, CapabilityOverride.empty(), policy(), OWNER,
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

        assertEquals(1L, configuration.getVersion());
        assertEquals("初始规则\n第二行",
                configuration.getOverride().getAdditionalRule().getValue());

        AdditionalCapabilityRule changedRule = AdditionalCapabilityRule.create(
                "变更后规则", 100);
        CapabilityOverride changed = CapabilityOverride.of(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                changedRule);
        configuration.changeOverride(
                1L, changed, policy(), OWNER, NOW.plusSeconds(1));

        assertEquals(2L, configuration.getVersion());
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

    @Test
    void expiredBaseProfileShouldRestoreDefaultWithExplicitWarning() {
        CapabilityOverride staleOverride =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                        Collections.<String>emptySet(),
                        Collections.singleton("retired-skill"),
                        Collections.singleton("retired-query"),
                        Collections.singleton("retired-rule"),
                        AdditionalCapabilityRule.create("旧版本附加要求", 100));
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1", staleOverride, OWNER, NOW,
                        7L, restorationPolicy());

        PhaseCapabilityOverrideResolution resolution =
                configuration.resolveFor(
                        WorkbenchId.of("workbench-1"), currentProfile());

        assertTrue(resolution.getEffectiveOverride()
                .getRemovedOptionalSkillIds().isEmpty());
        assertTrue(resolution.getEffectiveOverride()
                .getSelectedOptionalMcpIds().isEmpty());
        assertFalse(resolution.getEffectiveOverride()
                .hasExplicitOptionalMcpSelection());
        assertTrue(resolution.getEffectiveOverride()
                .getSelectedOptionalRuleIds().isEmpty());
        assertNull(resolution.getEffectiveOverride().getAdditionalRule());
        assertEquals(1, resolution.getIgnoredItems().size());
        assertEquals(
                PhaseCapabilityOverrideResolution.OverrideField.BASE_PROFILE,
                resolution.getIgnoredItems().get(0).getField());
        assertEquals("BASE_PROFILE_CHANGED",
                resolution.getIgnoredItems().get(0).getReasonCode());
        assertEquals(Collections.singletonList(
                        PhaseCapabilityOverrideResolution
                                .RESTORED_DEFAULT_WARNING),
                resolution.getWarnings());
    }

    @Test
    void invalidItemsShouldBeIgnoredWithoutReplacingCurrentProfileDefaults() {
        CapabilityOverride partlyStale = CapabilityOverride.restore(
                Collections.<String>emptySet(),
                new HashSet<String>(Arrays.asList(
                        "current-skill", "retired-skill")),
                Collections.singleton("retired-query"), true,
                Collections.singleton("retired-rule"), null);
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "2", partlyStale, OWNER, NOW,
                        8L, restorationPolicy());

        PhaseCapabilityOverrideResolution resolution =
                configuration.resolveFor(
                        WorkbenchId.of("workbench-1"), currentProfile());
        CapabilityOverride effective = resolution.getEffectiveOverride();

        assertEquals(Collections.singleton("current-skill"),
                effective.getRemovedOptionalSkillIds());
        assertFalse(effective.hasExplicitOptionalMcpSelection());
        assertTrue(effective.includes(reference(
                "current-query", PhaseCapabilityType.MCP_SERVER)));
        assertTrue(effective.includes(reference(
                "current-rule", PhaseCapabilityType.RULE)));
        assertFalse(effective.includes(reference(
                "current-skill", PhaseCapabilityType.SKILL)));
        assertEquals(3, resolution.getIgnoredItems().size());
        assertEquals(Arrays.asList(
                        "retired-skill", "retired-query", "retired-rule"),
                Arrays.asList(
                        resolution.getIgnoredItems().get(0).getCapabilityId(),
                        resolution.getIgnoredItems().get(1).getCapabilityId(),
                        resolution.getIgnoredItems().get(2).getCapabilityId()));
        assertEquals(Collections.singletonList(
                        PhaseCapabilityOverrideResolution
                                .PARTIAL_RESTORED_DEFAULT_WARNING),
                resolution.getWarnings());
    }

    @Test
    void changeAgainstUpgradedProfileShouldAtomicallyRebaseProfileAndOverride() {
        PhaseCapabilityProfile baseline = currentProfile();
        PhaseCapabilityProfile upgraded = PhaseCapabilityProfile.create(
                "review-profile-v2", "2", baseline.getPhase(),
                baseline.getCapabilities());
        PhaseCapabilityConfiguration configuration =
                PhaseCapabilityConfiguration.restore(
                        WorkbenchId.of("workbench-1"),
                        WorkbenchPhase.REVIEW_REFACTOR,
                        "review-profile", "1", CapabilityOverride.empty(),
                        OWNER, NOW, 7L, restorationPolicy());
        CapabilityOverride selected = upgraded.overrideWithSelectedOptionals(
                Collections.singleton("current-skill"),
                Collections.singleton("current-query"), null);

        configuration.changeOverride(
                7L, upgraded, selected, OWNER, NOW.plusSeconds(1));

        assertEquals("review-profile-v2", configuration.getBaseProfileId());
        assertEquals("2", configuration.getBaseProfileVersion());
        assertEquals(selected, configuration.getOverride());
        assertEquals(8L, configuration.getVersion());
        assertEquals(selected.getAddedOptionalSkillIds(),
                configuration.resolveFor(
                                WorkbenchId.of("workbench-1"), upgraded)
                        .getEffectiveOverride().getAddedOptionalSkillIds());
    }

    @Test
    void stateShouldDistinguishInitialAbsenceFromPersistedAndDeletedRevisions() {
        WorkbenchId workbenchId = WorkbenchId.of("workbench-1");
        PhaseCapabilityProfile profile = currentProfile();
        CapabilityOverride first = profile.overrideWithSelectedOptionals(
                Collections.singleton("current-skill"),
                Collections.<String>emptySet(), null);
        PhaseCapabilityConfigurationState state =
                PhaseCapabilityConfigurationState.initiallyAbsent(
                        workbenchId, WorkbenchPhase.REVIEW_REFACTOR);

        PhaseCapabilityConfiguration created = state.putOverride(
                0L, profile, first, OWNER, NOW);

        assertEquals(1L, created.getVersion());
        assertEquals(1L, state.getVersion());
        assertTrue(state.getConfiguration().isPresent());
        assertThrows(WorkbenchDomainException.class,
                () -> state.putOverride(
                        0L, profile, CapabilityOverride.empty(), OWNER,
                        NOW.plusSeconds(1)));

        PhaseCapabilityConfigurationState deleted =
                PhaseCapabilityConfigurationState.absent(
                        workbenchId, WorkbenchPhase.REVIEW_REFACTOR, 2L);
        assertFalse(deleted.resolveFor(profile).getEffectiveOverride()
                .hasExplicitOptionalMcpSelection());
        PhaseCapabilityConfiguration recreated = deleted.putOverride(
                2L, profile, CapabilityOverride.empty(), OWNER,
                NOW.plusSeconds(2));

        assertEquals(3L, recreated.getVersion());
        assertThrows(WorkbenchDomainException.class,
                () -> deleted.putOverride(
                        2L, profile, first, OWNER, NOW.plusSeconds(3)));
    }

    private static PhaseCapabilityProfile currentProfile() {
        return PhaseCapabilityProfile.create(
                "review-profile", "2", WorkbenchPhase.REVIEW_REFACTOR,
                Arrays.asList(
                        new PhaseCapabilityReference(
                                "platform-safety", PhaseCapabilityType.RULE,
                                true),
                        reference("current-skill", PhaseCapabilityType.SKILL),
                        reference("current-query",
                                PhaseCapabilityType.MCP_SERVER),
                        reference("current-rule", PhaseCapabilityType.RULE)));
    }

    private static PhaseCapabilityReference reference(
            String id, PhaseCapabilityType type) {
        return new PhaseCapabilityReference(id, type, false);
    }

    private static PhaseCapabilityOverridePolicy restorationPolicy() {
        return PhaseCapabilityOverridePolicy.constrainedTo(
                WorkbenchPhase.REVIEW_REFACTOR,
                new HashSet<String>(Arrays.asList(
                        "current-skill", "retired-skill")),
                new HashSet<String>(Arrays.asList(
                        "current-query", "retired-query")),
                new HashSet<String>(Arrays.asList(
                        "current-rule", "retired-rule")),
                Collections.singleton("platform-safety"));
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
