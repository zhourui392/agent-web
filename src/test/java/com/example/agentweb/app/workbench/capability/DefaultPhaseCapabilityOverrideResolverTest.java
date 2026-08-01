package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;
import com.example.agentweb.domain.workbench.PhaseCapabilityReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityType;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 默认阶段能力覆盖解析器的重复检测、Profile Policy 与附加规则边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class DefaultPhaseCapabilityOverrideResolverTest {

    private static final int MAX_ADDITIONAL_RULE_CHARS = 100;

    @Test
    void givenDuplicateIdInEachRawListWhenResolveThenFailClosedWithStableCode() {
        DefaultPhaseCapabilityOverrideResolver resolver = resolver();

        assertAll(
                () -> assertDuplicateRejected(resolver, selection(
                        Arrays.asList("refactor-assistant", "refactor-assistant"),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(), null)),
                () -> assertDuplicateRejected(resolver, selection(
                        Collections.<String>emptyList(),
                        Arrays.asList("optional-linter", "optional-linter"),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(), null)),
                () -> assertDuplicateRejected(resolver, selection(
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Arrays.asList("repository-query", "repository-query"),
                        Collections.<String>emptyList(), null)),
                () -> assertDuplicateRejected(resolver, selection(
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Collections.<String>emptyList(),
                        Arrays.asList(
                                "review/explain-structure",
                                "review/explain-structure"), null)));
    }

    @Test
    void givenValidSelectionWhenResolveThenCreateFourSetsAndNormalizedAdditionalRule() {
        CapabilityOverrideSelection selection = selection(
                Collections.singletonList("refactor-assistant"),
                Collections.singletonList("optional-linter"),
                Collections.singletonList("repository-query"),
                Collections.singletonList("review/explain-structure"),
                " \t\r\n第一行\r第二行\n ");

        CapabilityOverride result = resolver().resolve(profile(), selection);

        assertEquals(Collections.singleton("refactor-assistant"),
                result.getAddedOptionalSkillIds());
        assertEquals(Collections.singleton("optional-linter"),
                result.getRemovedOptionalSkillIds());
        assertEquals(Collections.singleton("repository-query"),
                result.getSelectedOptionalMcpIds());
        assertEquals(Collections.singleton("review/explain-structure"),
                result.getSelectedOptionalRuleIds());
        assertEquals("第一行\n第二行", result.getAdditionalRule().getValue());
    }

    @Test
    void selectedPublicOptionalsShouldMapToProfileConstrainedOverride() {
        CapabilityOverride result = resolver().resolveSelected(
                profile(),
                Collections.singletonList("refactor-assistant"),
                Collections.<String>emptyList(),
                " 仅执行聚焦测试 ");

        assertEquals(Collections.singleton("optional-linter"),
                result.getRemovedOptionalSkillIds());
        assertTrue(result.getAddedOptionalSkillIds().isEmpty());
        assertTrue(result.hasExplicitOptionalMcpSelection());
        assertTrue(result.getSelectedOptionalMcpIds().isEmpty());
        assertEquals("仅执行聚焦测试", result.getAdditionalRule().getValue());
        assertThrows(CapabilityCatalogException.class,
                () -> resolver().resolveSelected(
                        profile(),
                        Arrays.asList("refactor-assistant", "refactor-assistant"),
                        Collections.<String>emptyList(), null));
    }

    @Test
    void givenLegacyNullAndBlankAdditionalRulesWhenResolveThenRepresentNoRule() {
        CapabilityOverrideSelection legacy = new CapabilityOverrideSelection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList());
        CapabilityOverrideSelection explicitNull = selection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(), null);
        CapabilityOverrideSelection blank = selection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                " \t\r\n ");

        assertNull(legacy.getAdditionalRule());
        assertNull(explicitNull.getAdditionalRule());
        assertNull(resolver().resolve(profile(), legacy).getAdditionalRule());
        assertNull(resolver().resolve(profile(), explicitNull).getAdditionalRule());
        assertNull(resolver().resolve(profile(), blank).getAdditionalRule());
    }

    @Test
    void givenExplicitEmptyMcpSelectionWhenResolveThenKeepExplicitSelectionIntent() {
        CapabilityOverrideSelection selection = selection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                null);

        CapabilityOverride result = resolver().resolve(profile(), selection);

        assertTrue(result.hasExplicitOptionalMcpSelection());
        assertTrue(result.getSelectedOptionalMcpIds().isEmpty());
    }

    @Test
    void givenOversizedOrControlledAdditionalRuleWhenResolveThenDomainRejectsIt() {
        DefaultPhaseCapabilityOverrideResolver shortResolver =
                new DefaultPhaseCapabilityOverrideResolver(2);
        CapabilityOverrideSelection oversized = emptySelection("三个字");
        CapabilityOverrideSelection controlled = emptySelection(
                "规则" + ((char) 0x00));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> shortResolver.resolve(profile(), oversized)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> resolver().resolve(profile(), controlled)));
    }

    @Test
    void givenUnknownOrRequiredCapabilityIdWhenResolveThenProfilePolicyRejectsIt() {
        CapabilityOverrideSelection unknown = selection(
                Collections.singletonList("untrusted-local-skill"),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), null);
        CapabilityOverrideSelection requiredAsOptional = selection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.singletonList("platform-safety"), null);

        WorkbenchDomainException unknownFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> resolver().resolve(profile(), unknown));
        WorkbenchDomainException requiredFailure = assertThrows(
                WorkbenchDomainException.class,
                () -> resolver().resolve(profile(), requiredAsOptional));

        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                unknownFailure.getCode());
        assertEquals(WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                requiredFailure.getCode());
    }

    private static void assertDuplicateRejected(
            DefaultPhaseCapabilityOverrideResolver resolver,
            CapabilityOverrideSelection selection) {
        CapabilityCatalogException failure = assertThrows(
                CapabilityCatalogException.class,
                () -> resolver.resolve(profile(), selection));
        assertEquals("CAPABILITY_ID_DUPLICATE", failure.getCode());
    }

    private static DefaultPhaseCapabilityOverrideResolver resolver() {
        return new DefaultPhaseCapabilityOverrideResolver(
                MAX_ADDITIONAL_RULE_CHARS);
    }

    private static CapabilityOverrideSelection emptySelection(
            String additionalRule) {
        return selection(
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), Collections.<String>emptyList(),
                additionalRule);
    }

    private static CapabilityOverrideSelection selection(
            java.util.List<String> addedOptionalSkillIds,
            java.util.List<String> removedOptionalSkillIds,
            java.util.List<String> selectedOptionalMcpIds,
            java.util.List<String> selectedOptionalRuleIds,
            String additionalRule) {
        return new CapabilityOverrideSelection(
                addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, selectedOptionalRuleIds,
                additionalRule);
    }

    private static PhaseCapabilityProfile profile() {
        return PhaseCapabilityProfile.create(
                "review-profile", "1", WorkbenchPhase.REVIEW_REFACTOR,
                Arrays.asList(
                        new PhaseCapabilityReference(
                                "platform-safety", PhaseCapabilityType.RULE, true),
                        new PhaseCapabilityReference(
                                "refactor-assistant", PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "optional-linter", PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "repository-query", PhaseCapabilityType.MCP_SERVER, false),
                        new PhaseCapabilityReference(
                                "review/explain-structure", PhaseCapabilityType.RULE, false)));
    }
}
