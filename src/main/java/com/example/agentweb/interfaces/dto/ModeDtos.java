package com.example.agentweb.interfaces.dto;

import com.example.agentweb.app.mode.ModeQueryService;
import com.example.agentweb.app.mode.ModeView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 模式接口层 DTO。
 *
 * @author alex
 * @since 2026-08-20
 */
public final class ModeDtos {

    private ModeDtos() {
    }

    @Getter
    @Setter
    public static class SaveModeRequest {
        /** 仅创建时使用；编辑忽略（identifier 不可变）。 */
        @Size(max = 64, message = "identifier 最长 64 字符")
        private String identifier;

        @NotBlank(message = "displayName 必填")
        @Size(max = 128, message = "displayName 最长 128 字符")
        private String displayName;

        @Size(max = 2000, message = "description 最长 2000 字符")
        private String description;

        @Size(max = 16384, message = "defaultPrompt 最长 16384 字符")
        private String defaultPrompt;

        /** CLI 合法值: acceptEdits/auto/bypassPermissions/manual/dontAsk/plan；空 = 不传。 */
        private String permissionMode;

        @Size(max = 128, message = "model 最长 128 字符")
        private String model;

        /** low/medium/high；空 = 沿用 profile 默认。 */
        private String effort;

        private List<CapabilityRefRequest> commands;
        private List<CapabilityRefRequest> skills;
        private List<McpServerRefRequest> mcpServers;
    }

    @Getter
    @Setter
    public static class CapabilityRefRequest {
        @NotBlank
        private String identifier;
        @NotBlank
        private String version;
        @NotBlank
        private String contentHash;
        private int sortOrder;
    }

    @Getter
    @Setter
    public static class McpServerRefRequest {
        @NotBlank
        private String identifier;
        @NotBlank
        private String version;
        @NotBlank
        private String contentHash;
        @NotBlank
        private String maximumAccess;
        @NotBlank
        private String transport;
        private int sortOrder;
    }

    @Getter
    @Setter
    public static class ForkModeRequest {
        /** 模板复合身份之一：Stage Definition identifier。 */
        @NotBlank(message = "definitionIdentifier 必填")
        @Size(max = 128, message = "definitionIdentifier 最长 128 字符")
        private String definitionIdentifier;

        /** 模板复合身份之二：revision_number。 */
        private Long revisionId;

        @Size(max = 128, message = "displayName 最长 128 字符")
        private String displayName;
    }

    public record ModeResponse(
            String id, String identifier, String displayName, String description,
            String defaultPrompt, String permissionMode, String model, String effort,
            Long sourceRevisionId,
            List<ModeView.CommandRefView> commands,
            List<ModeView.SkillRefView> skills,
            List<ModeView.McpServerRefView> mcpServers) {

        public static ModeResponse from(ModeView view) {
            return new ModeResponse(view.id(), view.identifier(), view.displayName(),
                    view.description(), view.defaultPrompt(), view.permissionMode(),
                    view.model(), view.effort(), view.sourceRevisionId(),
                    view.commands(), view.skills(), view.mcpServers());
        }
    }

    public record TemplateResponse(
            long revisionId, String definitionIdentifier, String displayName,
            String description, int commandCount, int skillCount, int mcpServerCount) {

        public static TemplateResponse from(ModeQueryService.ModeTemplateView view) {
            return new TemplateResponse(view.revisionId(), view.definitionIdentifier(),
                    view.displayName(), view.description(), view.commandCount(),
                    view.skillCount(), view.mcpServerCount());
        }
    }

    public record CapabilityCatalogResponse(
            String kind, String identifier, String version, String displayName,
            String description, String hash, String maximumAccess, String transport) {

        public static CapabilityCatalogResponse from(
                ModeQueryService.ModeCapabilityCatalogItem item) {
            return new CapabilityCatalogResponse(item.kind(), item.identifier(),
                    item.version(), item.displayName(), item.description(), item.hash(),
                    item.maximumAccess(), item.transport());
        }
    }
}
