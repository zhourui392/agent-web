package com.example.agentweb.domain.agentrun;

import com.example.agentweb.domain.shared.AgentType;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime registration snapshot supplied to the domain catalog.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public final class AgentRuntimeAvailability {

    private final AgentType type;
    private final boolean available;
    private final boolean allEnvironments;
    private final Set<String> supportedEnvironments;

    private AgentRuntimeAvailability(AgentType type, boolean available,
                                     boolean allEnvironments, Set<String> environments) {
        this.type = Objects.requireNonNull(type, "type");
        this.available = available;
        this.allEnvironments = allEnvironments;
        this.supportedEnvironments = immutableEnvironments(environments);
    }

    public static AgentRuntimeAvailability availableEverywhere(AgentType type) {
        return new AgentRuntimeAvailability(type, true, true, Collections.emptySet());
    }

    public static AgentRuntimeAvailability availableOn(AgentType type, Set<String> environments) {
        if (environments == null || environments.isEmpty()) {
            throw new IllegalArgumentException("supported environments must not be empty");
        }
        return new AgentRuntimeAvailability(type, true, false, environments);
    }

    public static AgentRuntimeAvailability unavailable(AgentType type) {
        return new AgentRuntimeAvailability(type, false, false, Collections.emptySet());
    }

    public boolean supportsEnvironment(String environment) {
        return available && (allEnvironments || supportedEnvironments.contains(normalize(environment)));
    }

    private Set<String> immutableEnvironments(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String value : values) {
            String environment = normalize(value);
            if (environment.isEmpty()) {
                throw new IllegalArgumentException("supported environment must not be blank");
            }
            normalized.add(environment);
        }
        return Collections.unmodifiableSet(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
