package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreview;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewItem;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner 侧 Effective Phase Capability Profile 安全投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class EffectivePhaseCapabilityView {

    private final WorkbenchPhase phase;
    private final String status;
    private final String profileId;
    private final String profileVersion;
    private final String profileHash;
    private final List<CapabilityPreviewItemView> rules;
    private final List<CapabilityPreviewItemView> skills;
    private final List<CapabilityPreviewItemView> mcpServers;
    private final List<String> optionalSkillIds;
    private final List<String> optionalMcpServerIds;
    private final String additionalRule;
    private final long overrideVersion;
    private final List<String> warnings;
    private final String effectiveFrom;
    private final String activeRunSnapshotHash;

    private EffectivePhaseCapabilityView(
            PhaseCapabilityPreview preview, CapabilityOverride override,
            long overrideVersion, String activeRunSnapshotHash) {
        this.phase = preview.getPhase();
        this.status = preview.getStatus().name();
        this.profileId = preview.getProfileId();
        this.profileVersion = preview.getProfileVersion();
        this.profileHash = preview.getProfileHash();
        this.rules = itemViews(preview.getRules());
        this.skills = itemViews(preview.getSkills());
        this.mcpServers = itemViews(preview.getMcpServers());
        this.optionalSkillIds = stringCopy(
                preview.getSelectedOptionalSkillIds());
        this.optionalMcpServerIds = stringCopy(
                preview.getSelectedOptionalMcpIds());
        this.additionalRule = override.getAdditionalRule() == null
                ? "" : override.getAdditionalRule().getValue();
        this.overrideVersion = overrideVersion;
        this.warnings = stringCopy(preview.getWarnings());
        this.effectiveFrom = CapabilityOverrideEffectiveFrom.NEXT_RUN.name();
        this.activeRunSnapshotHash = activeRunSnapshotHash;
    }

    public static EffectivePhaseCapabilityView from(
            PhaseCapabilityPreview preview, CapabilityOverride override,
            long overrideVersion, String activeRunSnapshotHash) {
        return new EffectivePhaseCapabilityView(
                preview, override, overrideVersion,
                activeRunSnapshotHash);
    }

    private static List<CapabilityPreviewItemView> itemViews(
            List<PhaseCapabilityPreviewItem> values) {
        List<CapabilityPreviewItemView> result =
                new ArrayList<CapabilityPreviewItemView>();
        for (PhaseCapabilityPreviewItem value : values) {
            result.add(new CapabilityPreviewItemView(value));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> stringCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
