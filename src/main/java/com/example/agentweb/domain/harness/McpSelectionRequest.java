package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * MCP Catalog 求交所需的全部已验证事实。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class McpSelectionRequest {

    private final HarnessStage stage;
    private final AgentRuntime runtime;
    private final Set<String> requestedServerIds;
    private final Set<String> requiredServerIds;
    private final Set<String> grantedServerIds;
    private final Set<String> environmentAllowedServerIds;
    private final RuntimeEnforcementProfile enforcementProfile;

    public McpSelectionRequest(HarnessStage stage, AgentRuntime runtime,
                               Set<String> requestedServerIds, Set<String> requiredServerIds,
                               Set<String> grantedServerIds, Set<String> environmentAllowedServerIds,
                               RuntimeEnforcementProfile enforcementProfile) {
        if (stage == null || runtime == null || enforcementProfile == null) {
            throw new IllegalArgumentException("MCP stage, runtime and enforcement profile are required");
        }
        this.stage = stage;
        this.runtime = runtime;
        this.requestedServerIds = immutable(requestedServerIds, "requested MCP server");
        this.requiredServerIds = immutable(requiredServerIds, "required MCP server");
        this.grantedServerIds = immutable(grantedServerIds, "granted MCP server");
        this.environmentAllowedServerIds = immutable(
                environmentAllowedServerIds, "environment MCP server");
        this.enforcementProfile = enforcementProfile;
        if (!this.requestedServerIds.containsAll(this.requiredServerIds)) {
            throw new IllegalArgumentException("required MCP servers must also be requested");
        }
    }

    public boolean isRequired(String serverId) {
        return requiredServerIds.contains(serverId);
    }

    private Set<String> immutable(Set<String> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " set must not be null or contain null");
        }
        Set<String> copy = new LinkedHashSet<String>();
        for (String value : values) {
            copy.add(DomainText.require(value, name, 120));
        }
        return Collections.unmodifiableSet(copy);
    }
}
