package com.example.agentweb.infra.runtime.profile;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.RunMode;
import com.example.agentweb.app.runtime.port.AgentRuntimeSurface;
import lombok.Getter;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 启动时从 data/secrets.properties 加载的 Runtime Profile。
 * API Key 只保留在进程内 Profile 索引和一次子进程环境物化中。
 *
 * @author alex
 * @since 2026-08-07
 */
@Getter
public final class AgentRuntimeProfile {

    private final String profileId;
    private final AgentType agentType;
    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final Set<String> allowedModels;
    private final String defaultReasoningEffort;
    private final Set<String> allowedReasoningEfforts;
    private final String runtimeEnvironment;
    private final Set<AgentRuntimeSurface> supportedSurfaces;
    private final Set<RunMode> supportedRunModes;
    private final boolean enabled;

    public AgentRuntimeProfile(String profileId, AgentType agentType, String endpoint,
                               String apiKey, String defaultModel, Set<String> allowedModels,
                               String defaultReasoningEffort,
                               Set<String> allowedReasoningEfforts,
                               String runtimeEnvironment,
                               Set<AgentRuntimeSurface> supportedSurfaces,
                               Set<RunMode> supportedRunModes, boolean enabled) {
        this.profileId = DomainText.require(profileId, "runtime profile id", 128);
        this.agentType = agentType;
        if (agentType == null) {
            throw new IllegalArgumentException("runtime profile agent type is required");
        }
        this.endpoint = requireEndpoint(endpoint);
        this.apiKey = apiKey == null || apiKey.trim().isEmpty() ? null : apiKey.trim();
        this.defaultModel = DomainText.require(defaultModel, "runtime profile default model", 256);
        this.allowedModels = immutableStrings(allowedModels, this.defaultModel, "allowed models");
        this.defaultReasoningEffort = DomainText.require(
                defaultReasoningEffort, "runtime profile default reasoning effort", 64);
        this.allowedReasoningEfforts = immutableStrings(
                allowedReasoningEfforts, this.defaultReasoningEffort, "allowed reasoning efforts");
        this.runtimeEnvironment = runtimeEnvironment == null || runtimeEnvironment.isBlank()
                ? null : runtimeEnvironment.trim();
        this.supportedSurfaces = immutableSurfaces(supportedSurfaces);
        this.supportedRunModes = supportedRunModes == null || supportedRunModes.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.allOf(RunMode.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(supportedRunModes));
        this.enabled = enabled;
    }

    public boolean supports(AgentRuntimeSurface surface, RunMode runMode) {
        return enabled && supportedSurfaces.contains(surface)
                && supportedRunModes.contains(runMode);
    }

    public String resolveModel(String override) {
        String value = override == null || override.isBlank() ? defaultModel : override.trim();
        if (!allowedModels.contains(value)) {
            throw new AgentRuntimeProfileSelectionException(
                    "model is not allowed by runtime profile: " + profileId);
        }
        return value;
    }

    public String resolveReasoningEffort(String override) {
        String value = override == null || override.isBlank()
                ? defaultReasoningEffort : override.trim();
        if (!allowedReasoningEfforts.contains(value)) {
            throw new AgentRuntimeProfileSelectionException(
                    "reasoning effort is not allowed by runtime profile: " + profileId);
        }
        return value;
    }

    private static Set<String> immutableStrings(Set<String> values, String defaultValue, String name) {
        Set<String> copy = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    copy.add(value.trim());
                }
            }
        }
        copy.add(defaultValue);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Set<AgentRuntimeSurface> immutableSurfaces(Set<AgentRuntimeSurface> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("supported surfaces must not be empty");
        }
        for (AgentRuntimeSurface value : values) {
            if (value == null) {
                throw new IllegalArgumentException("supported surfaces must not contain null");
            }
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    private static String requireEndpoint(String value) {
        String normalized = DomainText.require(value, "runtime profile endpoint", 2048);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("runtime profile endpoint is invalid", ex);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())
                && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("runtime profile endpoint must use http or https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank() || isBlockedHost(host)) {
            throw new IllegalArgumentException("runtime profile endpoint host is not trusted");
        }
        return normalized;
    }

    private static boolean isBlockedHost(String host) {
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        if ("localhost".equals(normalized) || "::1".equals(normalized)
                || normalized.startsWith("127.")) {
            return true;
        }
        String[] octets = normalized.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        try {
            int first = Integer.parseInt(octets[0]);
            int second = Integer.parseInt(octets[1]);
            return first == 10 || first == 192 && second == 168
                    || first == 172 && second >= 16 && second <= 31
                    || first == 169 && second == 254;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
