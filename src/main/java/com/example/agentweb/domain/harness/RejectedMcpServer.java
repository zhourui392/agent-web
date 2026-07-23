package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 未进入 Snapshot 有效集合的 MCP Server 及稳定原因。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RejectedMcpServer {

    private final String id;
    private final String version;
    private final McpRejectionReason reason;

    public RejectedMcpServer(String id, String version, McpRejectionReason reason) {
        this.id = DomainText.require(id, "rejected MCP id", 120);
        this.version = DomainText.require(version, "rejected MCP version", 60);
        if (reason == null) {
            throw new IllegalArgumentException("MCP rejection reason must not be null");
        }
        this.reason = reason;
    }
}
