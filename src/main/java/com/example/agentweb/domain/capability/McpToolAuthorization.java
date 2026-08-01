package com.example.agentweb.domain.capability;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MCP Server 在单次最大访问级别下的 exact Tool allow/deny 决策。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class McpToolAuthorization {

    private final List<String> enabledToolNames;
    private final List<String> disabledToolNames;

    McpToolAuthorization(
            List<String> enabledToolNames, List<String> disabledToolNames) {
        if (enabledToolNames == null || enabledToolNames.isEmpty()
                || enabledToolNames.contains(null)
                || disabledToolNames == null || disabledToolNames.contains(null)) {
            throw new IllegalArgumentException(
                    "MCP Tool authorization must enable at least one exact Tool");
        }
        Set<String> enabled = new HashSet<String>(enabledToolNames);
        Set<String> disabled = new HashSet<String>(disabledToolNames);
        if (enabled.size() != enabledToolNames.size()
                || disabled.size() != disabledToolNames.size()
                || !Collections.disjoint(enabled, disabled)) {
            throw new IllegalArgumentException(
                    "MCP Tool authorization must be unique and disjoint");
        }
        this.enabledToolNames = Collections.unmodifiableList(
                new ArrayList<String>(enabledToolNames));
        this.disabledToolNames = Collections.unmodifiableList(
                new ArrayList<String>(disabledToolNames));
    }
}
