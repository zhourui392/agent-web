package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Skill 选择和能力求交所需的全部领域输入。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CapabilitySelectionRequest {

    private final HarnessStage stage;
    private final AgentRuntime runtime;
    private final Set<String> defaultSkillIds;
    private final Set<String> explicitSkillIds;
    private final Set<String> technicalTags;
    private final Set<String> approvedWorkspaceSkillIds;
    private final CapabilityGrant grant;

    public CapabilitySelectionRequest(HarnessStage stage, AgentRuntime runtime,
                                      Set<String> defaultSkillIds, Set<String> explicitSkillIds,
                                      Set<String> technicalTags, Set<String> approvedWorkspaceSkillIds,
                                      CapabilityGrant grant) {
        if (stage == null || runtime == null || grant == null) {
            throw new IllegalArgumentException("selection stage, runtime and grant must not be null");
        }
        this.stage = stage;
        this.runtime = runtime;
        this.defaultSkillIds = immutable(defaultSkillIds, false);
        this.explicitSkillIds = immutable(explicitSkillIds, false);
        this.technicalTags = immutable(technicalTags, true);
        this.approvedWorkspaceSkillIds = immutable(approvedWorkspaceSkillIds, false);
        this.grant = grant;
    }

    public boolean isWorkspaceApproved(String skillId) {
        return approvedWorkspaceSkillIds.contains(skillId);
    }

    private Set<String> immutable(Set<String> values, boolean lowercase) {
        if (values == null) {
            throw new IllegalArgumentException("selection set must not be null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            String normalized = DomainText.require(value, "selection value", 120);
            copy.add(lowercase ? normalized.toLowerCase(Locale.ROOT) : normalized);
        }
        return Collections.unmodifiableSet(copy);
    }
}
