package com.example.agentweb.app.mode;

import java.util.List;

/**
 * 管理后台全量模式读模型：模式视图 + 归属者信息。
 *
 * @author zhourui
 * @since 2026/08/22
 */
public record AdminModeView(
        String id,
        String identifier,
        String displayName,
        String description,
        String defaultPrompt,
        String permissionMode,
        String model,
        String effort,
        Long sourceRevisionId,
        List<ModeView.CommandRefView> commands,
        List<ModeView.SkillRefView> skills,
        List<ModeView.McpServerRefView> mcpServers,
        String ownerUserId,
        String ownerUsername) {

    /**
     * 由通用模式视图与归属者信息组装管理端读模型。
     *
     * @param mode 模式只读视图
     * @param ownerUserId 归属用户标识
     * @param ownerUsername 归属用户名；账号缺失时为空串
     * @return 管理端全量模式视图
     */
    public static AdminModeView from(ModeView mode, String ownerUserId, String ownerUsername) {
        return new AdminModeView(mode.id(), mode.identifier(), mode.displayName(),
                mode.description(), mode.defaultPrompt(), mode.permissionMode(),
                mode.model(), mode.effort(), mode.sourceRevisionId(),
                mode.commands(), mode.skills(), mode.mcpServers(),
                ownerUserId, ownerUsername);
    }
}
