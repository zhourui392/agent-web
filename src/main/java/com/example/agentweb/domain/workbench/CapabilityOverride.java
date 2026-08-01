package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 高级用户对阶段可选 Skill/MCP 与非安全偏好的受限覆盖。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class CapabilityOverride {

    private final Set<String> addedOptionalSkillIds;
    private final Set<String> removedOptionalSkillIds;
    private final Set<String> selectedOptionalMcpIds;
    private final boolean explicitOptionalMcpSelection;
    private final Set<String> selectedOptionalRuleIds;
    private final AdditionalCapabilityRule additionalRule;

    private CapabilityOverride(Set<String> addedOptionalSkillIds,
                               Set<String> removedOptionalSkillIds,
                               Set<String> selectedOptionalMcpIds,
                               boolean explicitOptionalMcpSelection,
                               Set<String> selectedOptionalRuleIds,
                               AdditionalCapabilityRule additionalRule) {
        this.addedOptionalSkillIds = immutableIds(
                addedOptionalSkillIds, "added optional skills", 50);
        this.removedOptionalSkillIds = immutableIds(
                removedOptionalSkillIds, "removed optional skills", 50);
        this.selectedOptionalMcpIds = immutableIds(
                selectedOptionalMcpIds, "selected optional MCP servers", 50);
        this.explicitOptionalMcpSelection = explicitOptionalMcpSelection;
        this.selectedOptionalRuleIds = immutableIds(
                selectedOptionalRuleIds, "selected optional rules", 50);
        this.additionalRule = additionalRule;
        Set<String> overlap = new HashSet<String>(this.addedOptionalSkillIds);
        overlap.retainAll(this.removedOptionalSkillIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "the same optional skill cannot be both added and removed");
        }
    }

    public static CapabilityOverride of(
            Set<String> addedOptionalSkillIds, Set<String> removedOptionalSkillIds,
            Set<String> selectedOptionalMcpIds, Set<String> selectedOptionalRuleIds) {
        return new CapabilityOverride(
                addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, selectedOptionalMcpIds != null
                        && !selectedOptionalMcpIds.isEmpty(),
                selectedOptionalRuleIds, null);
    }

    public static CapabilityOverride of(
            Set<String> addedOptionalSkillIds, Set<String> removedOptionalSkillIds,
            Set<String> selectedOptionalMcpIds, Set<String> selectedOptionalRuleIds,
            AdditionalCapabilityRule additionalRule) {
        return new CapabilityOverride(
                addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, selectedOptionalMcpIds != null
                        && !selectedOptionalMcpIds.isEmpty(),
                selectedOptionalRuleIds,
                additionalRule);
    }

    public static CapabilityOverride withExplicitOptionalMcpSelection(
            Set<String> addedOptionalSkillIds,
            Set<String> removedOptionalSkillIds,
            Set<String> selectedOptionalMcpIds,
            Set<String> selectedOptionalRuleIds,
            AdditionalCapabilityRule additionalRule) {
        return new CapabilityOverride(
                addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, true, selectedOptionalRuleIds,
                additionalRule);
    }

    public static CapabilityOverride restore(
            Set<String> addedOptionalSkillIds,
            Set<String> removedOptionalSkillIds,
            Set<String> selectedOptionalMcpIds,
            boolean explicitOptionalMcpSelection,
            Set<String> selectedOptionalRuleIds,
            AdditionalCapabilityRule additionalRule) {
        return new CapabilityOverride(
                addedOptionalSkillIds, removedOptionalSkillIds,
                selectedOptionalMcpIds, explicitOptionalMcpSelection,
                selectedOptionalRuleIds, additionalRule);
    }

    public static CapabilityOverride empty() {
        return new CapabilityOverride(
                Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), false,
                Collections.<String>emptySet(), null);
    }

    public boolean hasExplicitOptionalMcpSelection() {
        return explicitOptionalMcpSelection;
    }

    public boolean includesMcp(PhaseCapabilityReference reference) {
        if (reference == null
                || reference.getType() != PhaseCapabilityType.MCP_SERVER) {
            throw new IllegalArgumentException(
                    "MCP capability reference must not be null");
        }
        return reference.isRequired()
                || !explicitOptionalMcpSelection
                || selectedOptionalMcpIds.contains(reference.getId());
    }

    public boolean includes(PhaseCapabilityReference reference) {
        if (reference == null) {
            throw new IllegalArgumentException(
                    "capability reference must not be null");
        }
        if (reference.isRequired()) {
            return true;
        }
        switch (reference.getType()) {
            case RULE:
                return selectedOptionalRuleIds.isEmpty()
                        || selectedOptionalRuleIds.contains(reference.getId());
            case SKILL:
                return !removedOptionalSkillIds.contains(reference.getId());
            case MCP_SERVER:
                return includesMcp(reference);
            default:
                throw new IllegalStateException(
                        "unsupported phase capability type");
        }
    }

    private static Set<String> immutableIds(Set<String> ids, String name, int maximum) {
        if (ids == null || ids.contains(null) || ids.size() > maximum) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + maximum + " non-null ids");
        }
        Set<String> normalized = new HashSet<String>();
        for (String id : ids) {
            normalized.add(DomainText.require(id, name + " id", 160));
        }
        return Collections.unmodifiableSet(normalized);
    }

}
