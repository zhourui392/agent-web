package com.example.agentweb.infra.runtime.profile;

import com.example.agentweb.app.runtime.port.RuntimeSelection;
import com.example.agentweb.app.runtime.port.RuntimeVersionPolicy;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workbench.RunMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 启动时冻结的 Profile 索引；首期只做 fail-closed 唯一候选选择。
 *
 * @author alex
 * @since 2026-08-07
 */
public final class AgentRuntimeProfileCatalog {

    private final List<AgentRuntimeProfile> profiles;

    public AgentRuntimeProfileCatalog(List<AgentRuntimeProfile> profiles) {
        if (profiles == null) {
            throw new IllegalArgumentException("runtime profiles must be complete");
        }
        List<AgentRuntimeProfile> copy = new ArrayList<>(profiles);
        if (copy.stream().anyMatch(profile -> profile == null)) {
            throw new IllegalArgumentException("runtime profiles must be complete");
        }
        for (int i = 0; i < copy.size(); i++) {
            for (int j = i + 1; j < copy.size(); j++) {
                if (copy.get(i).getProfileId().equals(copy.get(j).getProfileId())) {
                    throw new IllegalArgumentException("duplicate runtime profile id");
                }
            }
        }
        this.profiles = Collections.unmodifiableList(copy);
    }

    public AgentRuntimeProfile byId(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        for (AgentRuntimeProfile profile : profiles) {
            if (profile.getProfileId().equals(profileId.trim())) {
                return profile;
            }
        }
        return null;
    }

    public AgentRuntimeProfile select(AgentType agentType, AgentRuntimeSurface surface,
                                      RunMode runMode, String profileId,
                                      String model, String reasoningEffort) {
        Objects.requireNonNull(agentType, "agent type");
        Objects.requireNonNull(surface, "runtime surface");
        Objects.requireNonNull(runMode, "run mode");
        List<AgentRuntimeProfile> candidates = new ArrayList<>();
        if (profileId != null && !profileId.isBlank()) {
            AgentRuntimeProfile selected = byId(profileId);
            if (selected != null && selected.getAgentType() == agentType
                    && selected.supports(surface, runMode)) {
                candidates.add(selected);
            }
        } else {
            for (AgentRuntimeProfile profile : profiles) {
                if (profile.getAgentType() == agentType && profile.supports(surface, runMode)) {
                    candidates.add(profile);
                }
            }
        }
        if (candidates.size() != 1) {
            throw new AgentRuntimeProfileSelectionException(
                    "runtime profile is unavailable or ambiguous for " + agentType);
        }
        AgentRuntimeProfile selected = candidates.get(0);
        selected.resolveModel(model);
        selected.resolveReasoningEffort(reasoningEffort);
        return selected;
    }

    public RuntimeSelection selection(AgentType agentType, AgentRuntimeSurface surface,
                                      RunMode runMode, String profileId,
                                      String model, String reasoningEffort) {
        AgentRuntimeProfile profile = select(agentType, surface, runMode, profileId,
                model, reasoningEffort);
        return new RuntimeSelection(profile.getProfileId(), profile.getAgentType(),
                profile.getEndpoint(), profile.resolveModel(model),
                profile.resolveReasoningEffort(reasoningEffort),
                profile.getRuntimeEnvironment(), RuntimeVersionPolicy.configured());
    }

    public List<AgentRuntimeProfile> profiles() {
        return profiles;
    }
}
