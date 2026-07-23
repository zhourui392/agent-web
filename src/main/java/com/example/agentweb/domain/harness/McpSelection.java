package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次 MCP 授权求交结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class McpSelection {

    private final List<SelectedMcpServer> selected;
    private final List<RejectedMcpServer> rejected;

    public McpSelection(List<SelectedMcpServer> selected, List<RejectedMcpServer> rejected) {
        this.selected = immutable(selected, "selected MCP servers");
        this.rejected = immutable(rejected, "rejected MCP servers");
    }

    public static McpSelection none() {
        return new McpSelection(Collections.<SelectedMcpServer>emptyList(),
                Collections.<RejectedMcpServer>emptyList());
    }

    private <T> List<T> immutable(List<T> values, String name) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(name + " must not be null or contain null");
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
