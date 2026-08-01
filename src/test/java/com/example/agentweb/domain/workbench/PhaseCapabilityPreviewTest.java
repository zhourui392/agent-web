package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import com.example.agentweb.domain.shared.CanonicalHashing;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effective Phase Capability Preview 的选择、降级与安全摘要测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityPreviewTest {

    @Test
    void shouldDescribeResolvedSelectedAndRejectedProfileCapabilities() {
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.singleton("repository-query"), null);
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", profile.getProfileId(),
                profile.getProfileVersion(), profile.getProfileHash(),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/workbench-safety", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("rule"), true,
                        "Workbench safety")),
                Collections.singletonList(new ResolvedSkillBinding(
                        "java-tdd", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("skill"), "PLATFORM")),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.singletonList(new RejectedCapability(
                        "repository-query", "OPTIONAL_MCP_UNAVAILABLE")),
                "CODEX_WORKBENCH@1");

        PhaseCapabilityPreview preview = PhaseCapabilityPreview.create(
                profile, override, binding);

        assertEquals(PhaseCapabilityPreviewStatus.DEGRADED, preview.getStatus());
        assertEquals(Collections.singletonList("java-tdd"),
                preview.getSelectedOptionalSkillIds());
        assertEquals(Collections.singletonList("repository-query"),
                preview.getSelectedOptionalMcpIds());
        assertEquals(Collections.singletonList(
                        "repository-query:OPTIONAL_MCP_UNAVAILABLE"),
                preview.getWarnings());
        assertEquals("PLATFORM", preview.getRules().get(0).getSource());
        assertEquals("Workbench safety", preview.getRules().get(0).getSummary());
        assertTrue(preview.getRules().get(0).isRequired());
        assertTrue(preview.getRules().get(0).isSelected());
        assertEquals("PLATFORM", preview.getSkills().get(0).getSource());
        assertTrue(preview.getSkills().get(0).isSelected());
        assertEquals("PHASE_PROFILE", preview.getSkills().get(1).getSource());
        assertFalse(preview.getSkills().get(1).isSelected());
        assertEquals("UNAVAILABLE", preview.getMcpServers().get(0).getSource());
        assertTrue(preview.getMcpServers().get(0).isSelected());
    }

    @Test
    void explicitEmptyMcpSelectionShouldNotCreateMissingCapabilityWarning() {
        PhaseCapabilityProfile profile = profile();
        CapabilityOverride override = profile.overrideWithSelectedOptionals(
                Collections.singleton("java-tdd"),
                Collections.<String>emptySet(), null);
        ResolvedCapabilityBinding binding = ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", profile.getProfileId(),
                profile.getProfileVersion(), profile.getProfileHash(),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/workbench-safety", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("rule"), true,
                        "Workbench safety")),
                Collections.singletonList(new ResolvedSkillBinding(
                        "java-tdd", "1.0.0", "PLATFORM",
                        CanonicalHashing.sha256("skill"), "PLATFORM")),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(),
                "CODEX_WORKBENCH@1");

        PhaseCapabilityPreview preview = PhaseCapabilityPreview.create(
                profile, override, binding);

        assertEquals(PhaseCapabilityPreviewStatus.AVAILABLE, preview.getStatus());
        assertTrue(preview.getWarnings().isEmpty());
        assertTrue(preview.getSelectedOptionalMcpIds().isEmpty());
        assertFalse(preview.getMcpServers().get(0).isSelected());
    }

    @Test
    void shouldFailClosedWhenBindingDoesNotBelongToProfile() {
        PhaseCapabilityProfile profile = profile();
        ResolvedCapabilityBinding mismatched = ResolvedCapabilityBinding.resolve(
                "workbench-policy@1", "other-profile", "1.0.0",
                CanonicalHashing.sha256("other-profile"),
                Collections.<ResolvedRuleBinding>emptyList(),
                Collections.<ResolvedSkillBinding>emptyList(),
                Collections.<ResolvedMcpServerBinding>emptyList(),
                Collections.<RejectedCapability>emptyList(),
                "CODEX_WORKBENCH@1");

        assertThrows(IllegalArgumentException.class,
                () -> PhaseCapabilityPreview.create(
                        profile, CapabilityOverride.empty(), mismatched));
    }

    private static PhaseCapabilityProfile profile() {
        return PhaseCapabilityProfile.create(
                "implement-profile", "1.0.0", WorkbenchPhase.IMPLEMENT_TEST,
                Arrays.asList(
                        new PhaseCapabilityReference(
                                "platform/workbench-safety",
                                PhaseCapabilityType.RULE, true),
                        new PhaseCapabilityReference(
                                "java-tdd", PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "regression-test", PhaseCapabilityType.SKILL, false),
                        new PhaseCapabilityReference(
                                "repository-query",
                                PhaseCapabilityType.MCP_SERVER, false)));
    }
}
