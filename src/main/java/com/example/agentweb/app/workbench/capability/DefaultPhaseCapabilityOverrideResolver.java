package com.example.agentweb.app.workbench.capability;

import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.workbench.AdditionalCapabilityRule;
import com.example.agentweb.domain.workbench.CapabilityOverride;
import com.example.agentweb.domain.workbench.PhaseCapabilityProfile;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 将接口层保留重复事实的原始选择转换为受 Profile Policy 约束的领域 Override。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class DefaultPhaseCapabilityOverrideResolver
        implements PhaseCapabilityOverrideResolver {

    private static final String DUPLICATE_ID = "CAPABILITY_ID_DUPLICATE";

    private final int maximumAdditionalRuleCharacters;

    public DefaultPhaseCapabilityOverrideResolver(
            int maximumAdditionalRuleCharacters) {
        AdditionalCapabilityRule.optional(
                null, maximumAdditionalRuleCharacters);
        this.maximumAdditionalRuleCharacters =
                maximumAdditionalRuleCharacters;
    }

    @Override
    public CapabilityOverride resolve(
            PhaseCapabilityProfile profile,
            CapabilityOverrideSelection selection) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(selection, "selection");
        CapabilityOverride override =
                CapabilityOverride.withExplicitOptionalMcpSelection(
                unique(selection.getAddedOptionalSkillIds()),
                unique(selection.getRemovedOptionalSkillIds()),
                unique(selection.getSelectedOptionalMcpIds()),
                unique(selection.getSelectedOptionalRuleIds()),
                AdditionalCapabilityRule.optional(
                        selection.getAdditionalRule(),
                        maximumAdditionalRuleCharacters));
        profile.getOverridePolicy().requireAllowed(
                profile.getPhase(), override);
        return override;
    }

    @Override
    public CapabilityOverride resolveSelected(
            PhaseCapabilityProfile profile,
            List<String> optionalSkillIds,
            List<String> optionalMcpServerIds,
            String additionalRule) {
        Objects.requireNonNull(profile, "profile");
        return profile.overrideWithSelectedOptionals(
                unique(Objects.requireNonNull(
                        optionalSkillIds, "optionalSkillIds")),
                unique(Objects.requireNonNull(
                        optionalMcpServerIds, "optionalMcpServerIds")),
                AdditionalCapabilityRule.optional(
                        additionalRule, maximumAdditionalRuleCharacters));
    }

    private Set<String> unique(List<String> identifiers) {
        Set<String> unique = new HashSet<String>(identifiers);
        if (unique.size() != identifiers.size()) {
            throw new CapabilityCatalogException(
                    DUPLICATE_ID,
                    "capability override contains a duplicate identifier");
        }
        return unique;
    }
}
