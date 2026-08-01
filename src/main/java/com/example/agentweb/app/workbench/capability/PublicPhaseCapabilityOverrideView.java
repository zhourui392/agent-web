package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.PhaseCapabilityOverrideResolution;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreview;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 不泄漏内部 add/remove/rule/audit 身份字段的公开 Override 投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PublicPhaseCapabilityOverrideView {

    private final List<String> optionalSkillIds;
    private final List<String> optionalMcpServerIds;
    private final String additionalRule;
    private final long version;
    private final long updatedAt;

    private PublicPhaseCapabilityOverrideView(
            PhaseCapabilityConfiguration configuration,
            PhaseCapabilityOverrideResolution overrideResolution,
            PhaseCapabilityPreview preview) {
        CapabilityOverride override =
                overrideResolution.getEffectiveOverride();
        this.optionalSkillIds = immutable(
                preview.getSelectedOptionalSkillIds());
        this.optionalMcpServerIds = immutable(
                preview.getSelectedOptionalMcpIds());
        this.additionalRule = override.getAdditionalRule() == null
                ? "" : override.getAdditionalRule().getValue();
        this.version = configuration.getVersion();
        this.updatedAt = configuration.getUpdatedAt().toEpochMilli();
    }

    public static PublicPhaseCapabilityOverrideView from(
            PhaseCapabilityConfiguration configuration,
            PhaseCapabilityOverrideResolution overrideResolution,
            PhaseCapabilityPreview preview) {
        return new PublicPhaseCapabilityOverrideView(
                configuration, overrideResolution, preview);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
