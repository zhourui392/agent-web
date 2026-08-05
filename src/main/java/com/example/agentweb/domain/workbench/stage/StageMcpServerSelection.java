package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Stage Draft 选择的 MCP Server 精确版本与必需性。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class StageMcpServerSelection {

    private final String identifier;
    private final String version;
    private final boolean required;

    public StageMcpServerSelection(String identifier, String version, boolean required) {
        this.identifier = DomainText.require(
                identifier, "Stage MCP Server identifier", 128);
        this.version = DomainText.require(version, "Stage MCP Server version", 80);
        this.required = required;
    }
}
