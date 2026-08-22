package com.example.agentweb.app.mode;

import java.util.List;

/**
 * 模式模板（原 Workbench Stage 定义 revision 重解释）与能力目录的读侧查询端口。
 *
 * @author alex
 * @since 2026-08-20
 */
public interface ModeQueryService {

    /** 当前用户的自定义模式列表。 */
    List<ModeView> listMine(String userId);

    /** 全部用户的模式列表（管理后台专用，含归属者信息）。 */
    List<AdminModeView> listAll();

    /** 模板库列表（已发布 revision，含能力计数）。 */
    List<ModeTemplateView> listTemplates();

    /** 模板 revision 的冻结能力清单，供 fork 拷贝；身份为复合键（definition_identifier + revision_number）。 */
    ModeTemplateRevisionView findTemplateRevision(String definitionIdentifier, long revisionId);

    /** 可选能力目录（命令 / Skill / MCP 当前可用版本），供模式编辑器选择。 */
    List<ModeCapabilityCatalogItem> listCapabilityCatalog();

    /**
     * 模板摘要视图。
     */
    record ModeTemplateView(
            long revisionId,
            String definitionIdentifier,
            String displayName,
            String description,
            int commandCount,
            int skillCount,
            int mcpServerCount) {
    }

    /**
     * 模板 revision 冻结能力（fork 源事实）。
     */
    record ModeTemplateRevisionView(
            long revisionId,
            String definitionIdentifier,
            String displayName,
            String description,
            String stageRules,
            List<ModeView.CommandRefView> commands,
            List<ModeView.SkillRefView> skills,
            List<ModeView.McpServerRefView> mcpServers) {
    }

    /**
     * 能力目录项。
     */
    record ModeCapabilityCatalogItem(
            String kind,
            String identifier,
            String version,
            String displayName,
            String description,
            String hash,
            String maximumAccess,
            String transport) {
    }
}
