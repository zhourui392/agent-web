package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.McpServerDefinition;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MCP JSON 的规范化文本和领域定义解析结果。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class ParsedMcpServerCatalog {

    private final String canonicalJson;
    private final List<McpServerDefinition> definitions;

    public ParsedMcpServerCatalog(
            String canonicalJson, List<McpServerDefinition> definitions) {
        if (canonicalJson == null || canonicalJson.trim().isEmpty()) {
            throw new IllegalArgumentException("canonical MCP JSON must not be blank");
        }
        if (definitions == null || definitions.contains(null)) {
            throw new IllegalArgumentException(
                    "MCP definitions must not be null or contain null");
        }
        this.canonicalJson = canonicalJson;
        this.definitions = Collections.unmodifiableList(
                new ArrayList<McpServerDefinition>(definitions));
    }
}
