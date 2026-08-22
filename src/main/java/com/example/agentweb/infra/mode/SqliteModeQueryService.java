package com.example.agentweb.infra.mode;

import com.example.agentweb.app.mode.AdminModeView;
import com.example.agentweb.app.mode.ModeQueryService;
import com.example.agentweb.app.mode.ModeView;
import com.example.agentweb.domain.capability.CommandCatalog;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.mode.ChatModeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 模式读侧查询：我的模式、模板库（stage catalog 重解释）、能力目录。
 *
 * @author alex
 * @since 2026-08-20
 */
@Component
public class SqliteModeQueryService implements ModeQueryService {

    private final JdbcTemplate jdbc;
    private final ChatModeRepository modeRepository;
    private final CommandCatalog commandCatalog;
    private final SkillCatalog skillCatalog;
    private final McpServerCatalog mcpServerCatalog;

    public SqliteModeQueryService(JdbcTemplate jdbc,
                                  ChatModeRepository modeRepository,
                                  CommandCatalog commandCatalog,
                                  SkillCatalog skillCatalog,
                                  McpServerCatalog mcpServerCatalog) {
        this.jdbc = jdbc;
        this.modeRepository = modeRepository;
        this.commandCatalog = commandCatalog;
        this.skillCatalog = skillCatalog;
        this.mcpServerCatalog = mcpServerCatalog;
    }

    @Override
    public List<ModeView> listMine(String userId) {
        List<String> ids = jdbc.query(
                "SELECT id FROM chat_mode WHERE user_id = ? ORDER BY updated_at DESC",
                (rs, n) -> rs.getString(1), userId);
        List<ModeView> views = new ArrayList<ModeView>();
        for (String id : ids) {
            modeRepository.find(id).ifPresent(mode -> views.add(ModeView.from(mode)));
        }
        return views;
    }

    @Override
    public List<AdminModeView> listAll() {
        record OwnerMode(String id, String ownerUserId, String ownerUsername) {
        }
        List<OwnerMode> rows = jdbc.query(
                "SELECT m.id AS mode_id, m.user_id AS owner_user_id, "
                        + "COALESCE(u.username, '') AS owner_username "
                        + "FROM chat_mode m LEFT JOIN user_account u ON u.id = m.user_id "
                        + "ORDER BY m.updated_at DESC",
                (rs, n) -> new OwnerMode(rs.getString("mode_id"),
                        rs.getString("owner_user_id"), rs.getString("owner_username")));
        List<AdminModeView> views = new ArrayList<AdminModeView>();
        for (OwnerMode row : rows) {
            modeRepository.find(row.id()).ifPresent(mode -> views.add(
                    AdminModeView.from(ModeView.from(mode),
                            row.ownerUserId(), row.ownerUsername())));
        }
        return views;
    }

    @Override
    public List<ModeTemplateView> listTemplates() {
        return jdbc.query(
                "SELECT r.definition_identifier, r.revision_number, r.display_name, r.description, "
                        + "(SELECT COUNT(*) FROM workbench_stage_definition_command c "
                        + " WHERE c.definition_identifier = r.definition_identifier "
                        + "   AND c.revision_number = r.revision_number) AS command_count, "
                        + "(SELECT COUNT(*) FROM workbench_stage_definition_skill s "
                        + " WHERE s.definition_identifier = r.definition_identifier "
                        + "   AND s.revision_number = r.revision_number) AS skill_count, "
                        + "(SELECT COUNT(*) FROM workbench_stage_definition_mcp_server m "
                        + " WHERE m.definition_identifier = r.definition_identifier "
                        + "   AND m.revision_number = r.revision_number) AS mcp_count "
                        + "FROM workbench_stage_definition_revision r "
                        + "JOIN workbench_stage_definition d "
                        + "  ON d.definition_identifier = r.definition_identifier "
                        + "WHERE d.current_published_revision = r.revision_number "
                        + "  AND d.disabled = 0 "
                        + "ORDER BY r.definition_identifier",
                (rs, n) -> new ModeTemplateView(
                        rs.getLong("revision_number"),
                        rs.getString("definition_identifier"),
                        rs.getString("display_name"),
                        rs.getString("description"),
                        rs.getInt("command_count"),
                        rs.getInt("skill_count"),
                        rs.getInt("mcp_count")));
    }

    @Override
    public ModeTemplateRevisionView findTemplateRevision(
            String definitionIdentifier, long revisionId) {
        List<ModeTemplateRevisionView> found = jdbc.query(
                "SELECT definition_identifier, revision_number, display_name, description, "
                        + "stage_rules "
                        + "FROM workbench_stage_definition_revision "
                        + "WHERE definition_identifier = ? AND revision_number = ?",
                (rs, n) -> templateRevision(rs), definitionIdentifier, revisionId);
        if (found.isEmpty()) {
            return null;
        }
        ModeTemplateRevisionView view = found.get(0);
        List<ModeView.CommandRefView> commands = jdbc.query(
                "SELECT capability_identifier, capability_version, capability_hash "
                        + "FROM workbench_stage_definition_command "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY command_order",
                (rs, n) -> new ModeView.CommandRefView(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                definitionIdentifier, revisionId);
        List<ModeView.SkillRefView> skills = jdbc.query(
                "SELECT capability_identifier, capability_version, capability_hash "
                        + "FROM workbench_stage_definition_skill "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY skill_order",
                (rs, n) -> new ModeView.SkillRefView(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                definitionIdentifier, revisionId);
        List<ModeView.McpServerRefView> mcpServers = jdbc.query(
                "SELECT capability_identifier, capability_version, capability_hash, "
                        + "maximum_access, transport "
                        + "FROM workbench_stage_definition_mcp_server "
                        + "WHERE definition_identifier = ? AND revision_number = ? "
                        + "ORDER BY mcp_order",
                (rs, n) -> new ModeView.McpServerRefView(
                        rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5)),
                definitionIdentifier, revisionId);
        return new ModeTemplateRevisionView(view.revisionId(), view.definitionIdentifier(),
                view.displayName(), view.description(), view.stageRules(),
                commands, skills, mcpServers);
    }

    private ModeTemplateRevisionView templateRevision(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ModeTemplateRevisionView(
                rs.getLong("revision_number"), rs.getString("definition_identifier"),
                rs.getString("display_name"), rs.getString("description"),
                rs.getString("stage_rules"),
                List.of(), List.of(), List.of());
    }

    @Override
    public List<ModeCapabilityCatalogItem> listCapabilityCatalog() {
        List<ModeCapabilityCatalogItem> items = new ArrayList<ModeCapabilityCatalogItem>();
        for (CommandDefinition command : safeDiscover(commandCatalog)) {
            items.add(new ModeCapabilityCatalogItem("COMMAND",
                    command.getIdentifier(), command.getVersion(),
                    command.getDisplayName(), command.getDescription(),
                    command.getContentHash(), null, null));
        }
        for (SkillPackage skill : safeDiscoverSkills()) {
            items.add(new ModeCapabilityCatalogItem("SKILL",
                    skill.getManifest().getId(), skill.getManifest().getVersion(),
                    skill.getManifest().getId(), skill.getManifest().getDescription(),
                    skill.getPackageHash(), null, null));
        }
        for (McpServerDefinition definition : safeDiscover(mcpServerCatalog)) {
            items.add(new ModeCapabilityCatalogItem("MCP_SERVER",
                    definition.getId(), definition.getVersion(),
                    definition.getId(), definition.getDescription(),
                    definition.getConfigurationHash(),
                    definition.getMaximumAccess().name(),
                    definition.getTransport().name()));
        }
        items.sort(Comparator.comparing(ModeCapabilityCatalogItem::kind)
                .thenComparing(ModeCapabilityCatalogItem::identifier));
        return items;
    }

    private List<CommandDefinition> safeDiscover(CommandCatalog catalog) {
        try {
            List<CommandDefinition> discovered = catalog.discover();
            return discovered == null ? List.of() : discovered;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<McpServerDefinition> safeDiscover(McpServerCatalog catalog) {
        try {
            List<McpServerDefinition> discovered = catalog.discover();
            return discovered == null ? List.of() : discovered;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<SkillPackage> safeDiscoverSkills() {
        try {
            List<SkillPackage> discovered = skillCatalog.discover();
            return discovered == null ? List.of() : discovered;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
