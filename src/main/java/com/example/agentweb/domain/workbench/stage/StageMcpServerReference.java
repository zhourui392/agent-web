package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Published Stage 引用的不可变 MCP Server Artifact。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageMcpServerReference {

    private final String identifier;
    private final String version;
    private final String definitionHash;
    private final boolean required;
    private final CapabilityAccess maximumAccess;
    private final String transport;

    public StageMcpServerReference(
            String identifier, String version, String definitionHash,
            boolean required, CapabilityAccess maximumAccess, String transport) {
        this.identifier = DomainText.require(
                identifier, "Stage MCP Server identifier", 128);
        this.version = DomainText.require(version, "Stage MCP Server version", 80);
        this.definitionHash = DomainText.requireSha256(
                definitionHash, "Stage MCP Server definition hash");
        if (maximumAccess == null || maximumAccess == CapabilityAccess.EXECUTE) {
            throw new IllegalArgumentException(
                    "Stage MCP Server access must be READ or WRITE");
        }
        this.maximumAccess = maximumAccess;
        this.transport = DomainText.require(transport, "Stage MCP Server transport", 80);
        this.required = required;
    }
}
