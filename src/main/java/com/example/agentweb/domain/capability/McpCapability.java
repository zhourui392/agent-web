package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;

import lombok.Getter;

/**
 * MCP Server 暴露的一项可独立授权能力。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class McpCapability {

    private final String id;
    private final McpCapabilityType type;
    private final CapabilityAccess access;

    public McpCapability(String id, McpCapabilityType type, CapabilityAccess access) {
        this.id = DomainText.require(id, "MCP capability id", 160);
        if (type == null || access == null || access == CapabilityAccess.EXECUTE) {
            throw new IllegalArgumentException("MCP capability type and READ/WRITE access are required");
        }
        this.type = type;
        this.access = access;
    }
}
