package com.example.agentweb.domain.harness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * MCP 信任 Catalog、阶段、显式 Grant、环境与 Runtime Enforcement 的整服求交策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public final class McpAuthorizationPolicy {

    public McpSelection select(McpSelectionRequest request, List<McpServerDefinition> catalog) {
        if (request == null || catalog == null || catalog.contains(null)) {
            throw new IllegalArgumentException("MCP request and catalog must not be null");
        }
        Map<String, List<McpServerDefinition>> indexed = index(catalog);
        List<SelectedMcpServer> selected = new ArrayList<SelectedMcpServer>();
        List<RejectedMcpServer> rejected = new ArrayList<RejectedMcpServer>();
        Set<String> orderedServerIds = new TreeSet<String>(request.getRequestedServerIds());
        for (String serverId : orderedServerIds) {
            List<McpServerDefinition> candidates = indexed.get(serverId);
            if (candidates == null || candidates.isEmpty()) {
                if (request.isRequired(serverId)) {
                    throw failure("MCP_NOT_FOUND", "required MCP server is missing: " + serverId);
                }
                continue;
            }
            if (candidates.size() != 1) {
                throw failure("MCP_VERSION_CONFLICT",
                        "multiple MCP server versions are present: " + serverId);
            }
            McpServerDefinition definition = candidates.get(0);
            McpRejectionReason reason = rejection(definition, request);
            if (reason == null) {
                selected.add(new SelectedMcpServer(definition, request.isRequired(serverId)));
            } else if (request.isRequired(serverId)) {
                throw failure("MCP_REQUIRED_DENIED",
                        "required MCP server was denied: " + serverId + " reason=" + reason);
            } else {
                rejected.add(new RejectedMcpServer(definition.getId(),
                        definition.getVersion(), reason));
            }
        }
        return new McpSelection(selected, rejected);
    }

    private Map<String, List<McpServerDefinition>> index(List<McpServerDefinition> catalog) {
        List<McpServerDefinition> ordered = new ArrayList<McpServerDefinition>(catalog);
        ordered.sort(Comparator.comparing(McpServerDefinition::getId)
                .thenComparing(McpServerDefinition::getVersion));
        Map<String, List<McpServerDefinition>> indexed =
                new LinkedHashMap<String, List<McpServerDefinition>>();
        for (McpServerDefinition definition : ordered) {
            indexed.computeIfAbsent(definition.getId(), ignored ->
                    new ArrayList<McpServerDefinition>()).add(definition);
        }
        return indexed;
    }

    private McpRejectionReason rejection(McpServerDefinition definition,
                                         McpSelectionRequest request) {
        if (!request.getGrantedServerIds().contains(definition.getId())) {
            return McpRejectionReason.NOT_GRANTED;
        }
        if (!request.getEnvironmentAllowedServerIds().contains(definition.getId())) {
            return McpRejectionReason.ENVIRONMENT_DENIED;
        }
        if (!definition.getApplicableStages().contains(request.getStage())) {
            return McpRejectionReason.STAGE_INCOMPATIBLE;
        }
        if (!definition.getRuntimes().contains(request.getRuntime())) {
            return McpRejectionReason.RUNTIME_INCOMPATIBLE;
        }
        if (definition.hasUnsupportedResourceCapability()) {
            return McpRejectionReason.RESOURCE_NOT_SUPPORTED;
        }
        if (definition.enabledReadToolNames().isEmpty()) {
            return McpRejectionReason.WRITE_NOT_SUPPORTED;
        }
        RuntimeEnforcementProfile profile = request.getEnforcementProfile();
        if (hasInsufficientEnforcement(profile)) {
            return McpRejectionReason.ENFORCEMENT_INSUFFICIENT;
        }
        return null;
    }

    private boolean hasInsufficientEnforcement(RuntimeEnforcementProfile profile) {
        return !profile.supportsMcpToolIsolation();
    }

    private CapabilityResolutionException failure(String code, String message) {
        return new CapabilityResolutionException(code, message);
    }
}
