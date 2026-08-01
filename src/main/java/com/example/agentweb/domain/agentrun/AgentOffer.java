package com.example.agentweb.domain.agentrun;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Effective product offer combining static policy and runtime registration.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class AgentOffer {

    private final AgentType type;
    private final String displayName;
    private final AgentPurpose purpose;
    private final Set<AgentSurface> exposedSurfaces;
    private final boolean userSelectable;
    private final boolean defaultEligible;
    private final AgentRuntimeAvailability runtime;

    AgentOffer(AgentType type, String displayName, AgentPurpose purpose,
               Set<AgentSurface> exposedSurfaces, boolean userSelectable,
               boolean defaultEligible, AgentRuntimeAvailability runtime) {
        this.type = Objects.requireNonNull(type, "type");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.purpose = Objects.requireNonNull(purpose, "purpose");
        this.exposedSurfaces = Collections.unmodifiableSet(EnumSet.copyOf(exposedSurfaces));
        this.userSelectable = userSelectable;
        this.defaultEligible = defaultEligible;
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public boolean isAvailable() {
        return runtime.isAvailable();
    }

    public boolean supportsEnvironment(String environment) {
        return runtime.supportsEnvironment(environment);
    }

    public Set<String> getSupportedEnvironments() {
        return runtime.getSupportedEnvironments();
    }

    public boolean supportsAllEnvironments() {
        return runtime.isAllEnvironments();
    }

    public void requireChatSelectable(String environment) {
        requireChatPolicy();
        requireRuntime();
        if (!runtime.supportsEnvironment(environment)) {
            throw new AgentRuntimeUnavailableException("AGENT_ENV_UNAVAILABLE",
                    "Agent " + type + " is unavailable for environment: " + environment);
        }
    }

    public void requireDefaultEligible() {
        if (!defaultEligible || !exposedSurfaces.contains(AgentSurface.DEFAULT)) {
            throw new AgentPolicyViolationException("AGENT_NOT_DEFAULT_ELIGIBLE",
                    "Agent " + type + " cannot be the default");
        }
    }

    public boolean supportsSurface(AgentSurface surface) {
        return surface != null && exposedSurfaces.contains(surface);
    }

    public void requireSurface(AgentSurface surface) {
        if (!supportsSurface(surface)) {
            throw new AgentPolicyViolationException("AGENT_SURFACE_UNAVAILABLE",
                    "Agent " + type + " is unavailable on surface: " + surface);
        }
    }

    public void requireAvailableForSurface(
            AgentSurface surface, String environment) {
        requireSurface(surface);
        requireRuntime();
        if (!runtime.supportsEnvironment(environment)) {
            throw new AgentRuntimeUnavailableException(
                    "AGENT_ENV_UNAVAILABLE",
                    "Agent " + type + " is unavailable for environment: "
                            + environment);
        }
    }

    private void requireChatPolicy() {
        if (!userSelectable || !exposedSurfaces.contains(AgentSurface.CHAT)) {
            throw new AgentPolicyViolationException("AGENT_NOT_USER_SELECTABLE",
                    "Agent " + type + " is not selectable for chat");
        }
    }

    private void requireRuntime() {
        if (!runtime.isAvailable()) {
            throw new AgentRuntimeUnavailableException("AGENT_RUNTIME_UNAVAILABLE",
                    "Agent runtime is unavailable: " + type);
        }
    }
}
