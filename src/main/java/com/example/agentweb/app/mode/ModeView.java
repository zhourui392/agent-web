package com.example.agentweb.app.mode;

import com.example.agentweb.domain.mode.ChatMode;
import com.example.agentweb.domain.mode.ModeCapabilities;

import java.util.List;

/**
 * 模式对外只读视图（我的模式 / 编辑回填）。
 *
 * @author alex
 * @since 2026-08-20
 */
public record ModeView(
        String id,
        String identifier,
        String displayName,
        String description,
        String defaultPrompt,
        String permissionMode,
        String model,
        String effort,
        Long sourceRevisionId,
        List<CommandRefView> commands,
        List<SkillRefView> skills,
        List<McpServerRefView> mcpServers) {

    public record CommandRefView(String identifier, String version, String contentHash) {
    }

    public record SkillRefView(String identifier, String version, String contentHash) {
    }

    public record McpServerRefView(String identifier, String version, String contentHash,
                                   String maximumAccess, String transport) {
    }

    public static ModeView from(ChatMode mode) {
        ModeCapabilities capabilities = mode.getCapabilities();
        return new ModeView(
                mode.getId(), mode.getIdentifier(), mode.getDisplayName(),
                mode.getDescription(), mode.getDefaultPrompt(),
                mode.getPermissionMode() == null ? null : mode.getPermissionMode().cliValue(),
                mode.getModel(), mode.getEffort(), mode.getSourceRevisionId(),
                capabilities.getCommands().stream()
                        .map(r -> new CommandRefView(r.getIdentifier(), r.getVersion(), r.getContentHash()))
                        .toList(),
                capabilities.getSkills().stream()
                        .map(r -> new SkillRefView(r.getIdentifier(), r.getVersion(), r.getContentHash()))
                        .toList(),
                capabilities.getMcpServers().stream()
                        .map(r -> new McpServerRefView(r.getIdentifier(), r.getVersion(),
                                r.getContentHash(), r.getMaximumAccess(), r.getTransport()))
                        .toList());
    }
}
