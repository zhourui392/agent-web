package com.example.agentweb.domain.workbench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一个 Phase Profile 已核验的可选能力边界；Override 只能在集合内收紧或选择。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseCapabilityOverridePolicy {

    private final WorkbenchPhase phase;
    private final Set<String> allowedOptionalSkillIds;
    private final Set<String> allowedOptionalMcpIds;
    private final Set<String> allowedOptionalRuleIds;
    private final Set<String> mandatoryCapabilityIds;

    private PhaseCapabilityOverridePolicy(
            WorkbenchPhase phase, Set<String> allowedOptionalSkillIds,
            Set<String> allowedOptionalMcpIds, Set<String> allowedOptionalRuleIds,
            Set<String> mandatoryCapabilityIds) {
        if (phase == null) {
            throw new IllegalArgumentException("capability policy phase must not be null");
        }
        this.phase = phase;
        this.allowedOptionalSkillIds = immutable(allowedOptionalSkillIds, "optional skills");
        this.allowedOptionalMcpIds = immutable(allowedOptionalMcpIds, "optional MCP servers");
        this.allowedOptionalRuleIds = immutable(allowedOptionalRuleIds, "optional rules");
        this.mandatoryCapabilityIds = immutable(mandatoryCapabilityIds, "mandatory capabilities");
    }

    public static PhaseCapabilityOverridePolicy constrainedTo(
            WorkbenchPhase phase, Set<String> allowedOptionalSkillIds,
            Set<String> allowedOptionalMcpIds, Set<String> allowedOptionalRuleIds,
            Set<String> mandatoryCapabilityIds) {
        return new PhaseCapabilityOverridePolicy(
                phase, allowedOptionalSkillIds,
                allowedOptionalMcpIds, allowedOptionalRuleIds, mandatoryCapabilityIds);
    }

    public void requireAllowed(WorkbenchPhase targetPhase, CapabilityOverride override) {
        if (targetPhase != phase || override == null) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "capability override policy does not match the phase");
        }
        if (!allowedOptionalSkillIds.containsAll(override.getAddedOptionalSkillIds())
                || !allowedOptionalSkillIds.containsAll(
                override.getRemovedOptionalSkillIds())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "capability override contains an untrusted or non-optional skill");
        }
        Set<String> mandatoryRemoval = new HashSet<String>(
                override.getRemovedOptionalSkillIds());
        mandatoryRemoval.retainAll(mandatoryCapabilityIds);
        if (!mandatoryRemoval.isEmpty()) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "capability override cannot remove mandatory capabilities");
        }
        if (!allowedOptionalMcpIds.containsAll(
                override.getSelectedOptionalMcpIds())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "capability override contains an untrusted MCP server");
        }
        if (!allowedOptionalRuleIds.containsAll(
                override.getSelectedOptionalRuleIds())) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "capability override contains an untrusted optional rule");
        }
    }

    PhaseCapabilityOverrideResolution reconcile(
            WorkbenchPhase targetPhase, CapabilityOverride override) {
        if (targetPhase != phase || override == null) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        List<PhaseCapabilityOverrideResolution.IgnoredItem> ignored =
                new ArrayList<PhaseCapabilityOverrideResolution.IgnoredItem>();
        Set<String> addedSkills = retainAllowed(
                override.getAddedOptionalSkillIds(), allowedOptionalSkillIds,
                PhaseCapabilityOverrideResolution.OverrideField
                        .ADDED_OPTIONAL_SKILL,
                ignored);
        Set<String> removedSkills = retainAllowed(
                override.getRemovedOptionalSkillIds(), allowedOptionalSkillIds,
                PhaseCapabilityOverrideResolution.OverrideField
                        .REMOVED_OPTIONAL_SKILL,
                ignored);
        int ignoredBeforeMcp = ignored.size();
        Set<String> selectedMcp = retainAllowed(
                override.getSelectedOptionalMcpIds(), allowedOptionalMcpIds,
                PhaseCapabilityOverrideResolution.OverrideField
                        .SELECTED_OPTIONAL_MCP,
                ignored);
        boolean explicitMcpSelection =
                override.hasExplicitOptionalMcpSelection();
        if (explicitMcpSelection
                && !override.getSelectedOptionalMcpIds().isEmpty()
                && selectedMcp.isEmpty()
                && ignored.size() > ignoredBeforeMcp) {
            explicitMcpSelection = false;
        }
        Set<String> selectedRules = retainAllowed(
                override.getSelectedOptionalRuleIds(), allowedOptionalRuleIds,
                PhaseCapabilityOverrideResolution.OverrideField
                        .SELECTED_OPTIONAL_RULE,
                ignored);
        CapabilityOverride effective = CapabilityOverride.restore(
                addedSkills, removedSkills, selectedMcp,
                explicitMcpSelection, selectedRules,
                override.getAdditionalRule());
        requireAllowed(targetPhase, effective);
        return PhaseCapabilityOverrideResolution.filtered(
                effective, ignored);
    }

    private Set<String> retainAllowed(
            Set<String> requested, Set<String> allowed,
            PhaseCapabilityOverrideResolution.OverrideField field,
            List<PhaseCapabilityOverrideResolution.IgnoredItem> ignored) {
        Set<String> effective = new HashSet<String>();
        for (String id : new TreeSet<String>(requested)) {
            if (allowed.contains(id)) {
                effective.add(id);
            } else {
                ignored.add(PhaseCapabilityOverrideResolution.ignored(
                        field, id));
            }
        }
        return effective;
    }

    private static Set<String> immutable(Set<String> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not contain null");
        }
        return Collections.unmodifiableSet(new HashSet<String>(values));
    }
}
