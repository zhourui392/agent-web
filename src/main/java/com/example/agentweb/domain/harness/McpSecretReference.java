package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * MCP 进程环境变量到受控 Secret Provider 逻辑键的映射，不包含 Secret 明文。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class McpSecretReference {

    private final String environmentVariable;
    private final String reference;

    public McpSecretReference(String environmentVariable, String reference) {
        this.environmentVariable = DomainText.require(
                environmentVariable, "MCP secret environment variable", 160);
        this.reference = DomainText.require(reference, "MCP secret reference", 240);
    }
}
