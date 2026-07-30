package com.example.agentweb.infra.nativeagent;

import java.util.Objects;
import java.util.Set;

/**
 * Marker proving that a fully configured NATIVE runtime is registered.
 *
 * @author alex
 * @since 2026-07-29
 */
public record NativeRuntimeRegistration(Set<String> boundEnvironments,
                                        Set<String> operationalEnvironments) {

    public NativeRuntimeRegistration {
        boundEnvironments = normalized(boundEnvironments, "boundEnvironments");
        operationalEnvironments = normalized(
                operationalEnvironments, "operationalEnvironments");
        if (boundEnvironments.isEmpty()
                || boundEnvironments.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("boundEnvironments must not be empty or blank");
        }
        if (!boundEnvironments.containsAll(operationalEnvironments)) {
            throw new IllegalArgumentException(
                    "operationalEnvironments must be a subset of boundEnvironments");
        }
    }

    public NativeRuntimeRegistration(Set<String> boundEnvironments) {
        this(boundEnvironments, boundEnvironments);
    }

    public NativeRuntimeRegistration(String boundEnvironment) {
        this(Set.of(boundEnvironment));
    }

    public String boundEnvironment() {
        if (boundEnvironments.size() != 1) {
            throw new IllegalStateException("multiple NATIVE environments are registered");
        }
        return boundEnvironments.iterator().next();
    }

    private static Set<String> normalized(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream()
                .map(value -> Objects.requireNonNull(value, name).trim())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
