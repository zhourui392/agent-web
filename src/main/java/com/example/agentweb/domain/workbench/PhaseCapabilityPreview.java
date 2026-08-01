package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.RejectedCapability;
import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedMcpServerBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.capability.ResolvedSkillBinding;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Profile、当前 Override 与可信 Binding 合成的领域级安全预览。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityPreview {

    private static final String PROFILE_SOURCE = "PHASE_PROFILE";
    private static final String UNAVAILABLE_SOURCE = "UNAVAILABLE";
    private static final String MCP_CATALOG_SOURCE = "MCP_CATALOG";

    private final WorkbenchPhase phase;
    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final PhaseCapabilityPreviewStatus status;
    private final List<PhaseCapabilityPreviewItem> rules;
    private final List<PhaseCapabilityPreviewItem> skills;
    private final List<PhaseCapabilityPreviewItem> mcpServers;
    private final List<String> selectedOptionalSkillIds;
    private final List<String> selectedOptionalMcpIds;
    private final List<String> warnings;

    private PhaseCapabilityPreview(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            ResolvedCapabilityBinding binding) {
        requireMatchingProfile(profile, binding);
        this.phase = profile.getPhase();
        this.profileId = profile.getProfileId();
        this.profileVersion = profile.getProfileVersion();
        this.profileHash = profile.getProfileHash();

        Map<String, ItemFacts> resolved = resolvedFacts(binding);
        Map<String, String> rejected = rejectedReasons(binding);
        List<PhaseCapabilityPreviewItem> ruleItems =
                new ArrayList<PhaseCapabilityPreviewItem>();
        List<PhaseCapabilityPreviewItem> skillItems =
                new ArrayList<PhaseCapabilityPreviewItem>();
        List<PhaseCapabilityPreviewItem> mcpItems =
                new ArrayList<PhaseCapabilityPreviewItem>();
        List<String> selectedSkills = new ArrayList<String>();
        List<String> selectedMcpServers = new ArrayList<String>();
        for (PhaseCapabilityReference reference : profile.getCapabilities()) {
            PhaseCapabilityPreviewItem item = previewItem(
                    reference, override, resolved.get(reference.getId()),
                    rejected.get(reference.getId()));
            switch (reference.getType()) {
                case RULE:
                    ruleItems.add(item);
                    break;
                case SKILL:
                    skillItems.add(item);
                    if (!item.isRequired() && item.isSelected()) {
                        selectedSkills.add(item.getId());
                    }
                    break;
                case MCP_SERVER:
                    mcpItems.add(item);
                    if (!item.isRequired() && item.isSelected()) {
                        selectedMcpServers.add(item.getId());
                    }
                    break;
                default:
                    throw new IllegalStateException(
                            "unsupported phase capability type");
            }
        }
        this.rules = immutable(ruleItems);
        this.skills = immutable(skillItems);
        this.mcpServers = immutable(mcpItems);
        this.selectedOptionalSkillIds = immutableStrings(selectedSkills);
        this.selectedOptionalMcpIds = immutableStrings(selectedMcpServers);
        this.warnings = warnings(binding);
        this.status = warnings.isEmpty()
                ? PhaseCapabilityPreviewStatus.AVAILABLE
                : PhaseCapabilityPreviewStatus.DEGRADED;
    }

    public static PhaseCapabilityPreview create(
            PhaseCapabilityProfile profile, CapabilityOverride override,
            ResolvedCapabilityBinding binding) {
        if (profile == null || override == null || binding == null) {
            throw new IllegalArgumentException(
                    "capability preview inputs must not be null");
        }
        profile.getOverridePolicy().requireAllowed(profile.getPhase(), override);
        return new PhaseCapabilityPreview(profile, override, binding);
    }

    private PhaseCapabilityPreviewItem previewItem(
            PhaseCapabilityReference reference, CapabilityOverride override,
            ItemFacts resolved, String rejectionReason) {
        boolean selected = resolved != null || rejectionReason != null;
        if (!selected && reference.isRequired()) {
            throw new IllegalArgumentException(
                    "required capability is absent from resolved binding");
        }
        if (!selected && override.includes(reference)) {
            throw new IllegalArgumentException(
                    "selected capability is absent from resolved binding");
        }
        String source = resolved == null
                ? rejectionReason == null ? PROFILE_SOURCE : UNAVAILABLE_SOURCE
                : resolved.getSource();
        String summary = resolved == null ? null : resolved.getSummary();
        return new PhaseCapabilityPreviewItem(
                reference.getId(), reference.isRequired(), selected,
                source, summary);
    }

    private static Map<String, ItemFacts> resolvedFacts(
            ResolvedCapabilityBinding binding) {
        Map<String, ItemFacts> result = new HashMap<String, ItemFacts>();
        for (ResolvedRuleBinding rule : binding.getRules()) {
            result.put(rule.getId(), new ItemFacts(
                    rule.getSource(), rule.getSafeSummary()));
        }
        for (ResolvedSkillBinding skill : binding.getSkills()) {
            result.put(skill.getId(), new ItemFacts(skill.getSource(), null));
        }
        for (ResolvedMcpServerBinding mcp : binding.getMcpServers()) {
            result.put(mcp.getId(), new ItemFacts(MCP_CATALOG_SOURCE, null));
        }
        return result;
    }

    private static Map<String, String> rejectedReasons(
            ResolvedCapabilityBinding binding) {
        Map<String, String> result = new HashMap<String, String>();
        for (RejectedCapability rejection : binding.getRejected()) {
            result.put(rejection.getId(), rejection.getReasonCode());
        }
        return result;
    }

    private static List<String> warnings(ResolvedCapabilityBinding binding) {
        List<String> result = new ArrayList<String>();
        for (RejectedCapability rejection : binding.getRejected()) {
            result.add(rejection.getId() + ":" + rejection.getReasonCode());
        }
        return immutableStrings(result);
    }

    private static void requireMatchingProfile(
            PhaseCapabilityProfile profile,
            ResolvedCapabilityBinding binding) {
        if (!profile.getProfileId().equals(binding.getProfileId())
                || !profile.getProfileVersion().equals(
                binding.getProfileVersion())
                || !profile.getProfileHash().equals(binding.getProfileHash())) {
            throw new IllegalArgumentException(
                    "capability binding does not belong to the profile");
        }
    }

    private static List<PhaseCapabilityPreviewItem> immutable(
            List<PhaseCapabilityPreviewItem> values) {
        return Collections.unmodifiableList(
                new ArrayList<PhaseCapabilityPreviewItem>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static final class ItemFacts {

        private final String source;
        private final String summary;

        private ItemFacts(String source, String summary) {
            this.source = source;
            this.summary = summary;
        }

        private String getSource() {
            return source;
        }

        private String getSummary() {
            return summary;
        }
    }
}
