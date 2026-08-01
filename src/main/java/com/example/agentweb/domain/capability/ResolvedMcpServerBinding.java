package com.example.agentweb.domain.capability;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 一项已解析且不含 Secret 明文的 MCP Server 绑定。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class ResolvedMcpServerBinding {

    private final String id;
    private final String version;
    private final String definitionHash;
    private final CapabilityAccess access;
    private final String transport;

    public ResolvedMcpServerBinding(String id, String version, String definitionHash,
                                    CapabilityAccess access, String transport) {
        this.id = DomainText.require(id, "MCP server id", 160);
        this.version = DomainText.require(version, "MCP server version", 80);
        this.definitionHash = DomainText.requireSha256(definitionHash, "MCP definition hash");
        if (access == null) {
            throw new IllegalArgumentException("MCP access must not be null");
        }
        this.access = access;
        this.transport = DomainText.require(transport, "MCP transport", 120);
    }
}
