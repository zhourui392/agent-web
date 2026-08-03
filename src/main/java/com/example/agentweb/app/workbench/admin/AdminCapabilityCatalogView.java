package com.example.agentweb.app.workbench.admin;

import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 管理后台可选 Skill / MCP Server / Rule Catalog 视图。
 *
 * <p>供管理后台编辑 Phase Profile 时从可信 Catalog 中选择能力。
 * Rule Catalog 无 discover 端口，仅列出 Skill 和 MCP Server。</p>
 *
 * @author alex
 * @since 2026-08-02
 */
@Getter
public final class AdminCapabilityCatalogView {

    private final List<CatalogEntry> skills;
    private final List<CatalogEntry> mcpServers;

    private AdminCapabilityCatalogView(
            List<CatalogEntry> skills, List<CatalogEntry> mcpServers) {
        this.skills = Collections.unmodifiableList(skills);
        this.mcpServers = Collections.unmodifiableList(mcpServers);
    }

    public static AdminCapabilityCatalogView of(
            List<CatalogEntry> skills, List<CatalogEntry> mcpServers) {
        return new AdminCapabilityCatalogView(skills, mcpServers);
    }

    /**
     * Catalog 条目视图。
     */
    @Getter
    public static final class CatalogEntry {
        private final String id;
        private final String version;
        private final String description;
        private final List<String> compatibleRuntimes;

        public CatalogEntry(String id, String version, String description,
                            List<String> compatibleRuntimes) {
            this.id = id;
            this.version = version;
            this.description = description;
            this.compatibleRuntimes = compatibleRuntimes != null
                    ? Collections.unmodifiableList(compatibleRuntimes)
                    : Collections.<String>emptyList();
        }
    }
}
