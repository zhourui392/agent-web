package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.PhaseCapabilityConfiguration;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Phase Capability Configuration 的只读应用投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseCapabilityOverrideView {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String baseProfileId;
    private final String baseProfileVersion;
    private final Set<String> addedOptionalSkillIds;
    private final Set<String> removedOptionalSkillIds;
    private final Set<String> selectedOptionalMcpIds;
    private final Set<String> selectedOptionalRuleIds;
    private final OwnerReference updatedBy;
    private final Instant updatedAt;
    private final long version;

    private PhaseCapabilityOverrideView(
            PhaseCapabilityConfiguration configuration) {
        this.workbenchId = configuration.getWorkbenchId();
        this.phase = configuration.getPhase();
        this.baseProfileId = configuration.getBaseProfileId();
        this.baseProfileVersion = configuration.getBaseProfileVersion();
        this.addedOptionalSkillIds = immutableCopy(
                configuration.getOverride().getAddedOptionalSkillIds());
        this.removedOptionalSkillIds = immutableCopy(
                configuration.getOverride().getRemovedOptionalSkillIds());
        this.selectedOptionalMcpIds = immutableCopy(
                configuration.getOverride().getSelectedOptionalMcpIds());
        this.selectedOptionalRuleIds = immutableCopy(
                configuration.getOverride().getSelectedOptionalRuleIds());
        this.updatedBy = configuration.getUpdatedBy();
        this.updatedAt = configuration.getUpdatedAt();
        this.version = configuration.getVersion();
    }

    public static PhaseCapabilityOverrideView from(
            PhaseCapabilityConfiguration configuration) {
        return new PhaseCapabilityOverrideView(
                Objects.requireNonNull(configuration, "configuration"));
    }

    private static Set<String> immutableCopy(Set<String> values) {
        return Collections.unmodifiableSet(new HashSet<String>(values));
    }
}
