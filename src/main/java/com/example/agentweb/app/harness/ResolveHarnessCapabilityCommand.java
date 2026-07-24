package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.CapabilityGrant;
import com.example.agentweb.domain.harness.HarnessStage;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 为当前 Stage Attempt 固化 Capability Snapshot 的应用命令。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class ResolveHarnessCapabilityCommand {

    private final String runId;
    private final HarnessStage stage;
    private final Set<String> explicitSkillIds;
    private final Set<String> technicalTags;
    private final Set<String> approvedWorkspaceSkillIds;
    private final CapabilityGrant capabilityGrant;
    private final Set<String> explicitMcpServerIds;
    private final Set<String> requiredMcpServerIds;
    private final Set<String> grantedMcpServerIds;
    private final String currentInput;

    public ResolveHarnessCapabilityCommand(String runId, HarnessStage stage,
                                           Set<String> explicitSkillIds, Set<String> technicalTags,
                                           Set<String> approvedWorkspaceSkillIds,
                                           CapabilityGrant capabilityGrant,
                                           String currentInput) {
        this(runId, stage, explicitSkillIds, technicalTags, approvedWorkspaceSkillIds,
                capabilityGrant, Collections.<String>emptySet(), Collections.<String>emptySet(),
                Collections.<String>emptySet(), currentInput);
    }

    public ResolveHarnessCapabilityCommand(String runId, HarnessStage stage,
                                           Set<String> explicitSkillIds, Set<String> technicalTags,
                                           Set<String> approvedWorkspaceSkillIds,
                                           CapabilityGrant capabilityGrant,
                                           Set<String> explicitMcpServerIds,
                                           Set<String> requiredMcpServerIds,
                                           Set<String> grantedMcpServerIds,
                                           String currentInput) {
        this.runId = require(runId, "run id");
        if (stage == null || capabilityGrant == null) {
            throw new IllegalArgumentException("stage and capability grant must not be null");
        }
        this.stage = stage;
        this.explicitSkillIds = immutable(explicitSkillIds);
        this.technicalTags = immutable(technicalTags);
        this.approvedWorkspaceSkillIds = immutable(approvedWorkspaceSkillIds);
        this.capabilityGrant = capabilityGrant;
        this.explicitMcpServerIds = immutable(explicitMcpServerIds);
        this.requiredMcpServerIds = immutable(requiredMcpServerIds);
        this.grantedMcpServerIds = immutable(grantedMcpServerIds);
        if (!this.explicitMcpServerIds.containsAll(this.requiredMcpServerIds)) {
            throw new IllegalArgumentException("required MCP servers must also be explicit");
        }
        this.currentInput = require(currentInput, "current input");
    }

    private Set<String> immutable(Set<String> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException("capability command set must not be null or contain null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            copy.add(require(value, "capability command value"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private String require(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
