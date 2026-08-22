package com.example.agentweb.app.mode;

import java.util.List;

/**
 * 创建 / 编辑模式的入参（能力引用为 Registry 冻结三元组，服务端重验）。
 *
 * @author alex
 * @since 2026-08-20
 */
public record SaveModeCommand(
        String identifier,
        String displayName,
        String description,
        String defaultPrompt,
        String permissionMode,
        String model,
        String effort,
        List<CapabilityRefInput> commands,
        List<CapabilityRefInput> skills,
        List<McpServerRefInput> mcpServers) {

    public record CapabilityRefInput(String identifier, String version, String contentHash, int sortOrder) {
    }

    public record McpServerRefInput(String identifier, String version, String contentHash,
                                    String maximumAccess, String transport, int sortOrder) {
    }
}
