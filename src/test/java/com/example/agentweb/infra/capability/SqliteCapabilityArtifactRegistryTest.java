package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.CapabilityKind;
import com.example.agentweb.domain.capability.CapabilityRequest;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpSecretReference;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.McpTransport;
import com.example.agentweb.domain.capability.SkillDependency;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不可变 Capability Artifact Registry 的 SQLite 与文件系统集成测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteCapabilityArtifactRegistryTest {

    @TempDir
    Path tempDirectory;

    private JdbcTemplate jdbcTemplate;
    private Path artifactRoot;
    private SqliteCapabilityArtifactRegistry registry;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDirectory.resolve("artifacts.db"));
        jdbcTemplate = new JdbcTemplate(dataSource);
        createTables();
        artifactRoot = tempDirectory.resolve("artifact-root");
        registry = new SqliteCapabilityArtifactRegistry(
                jdbcTemplate, new ObjectMapper(), artifactRoot,
                Clock.fixed(Instant.parse("2026-08-05T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void should_RoundTripExactCommandSkillAndMcp_When_SourceIsNoLongerAvailable()
            throws Exception {
        // Given
        CommandDefinition command = command("explain architecture");
        SkillPackage skill = skillPackage();
        McpServerDefinition mcp = mcpServer();

        // When
        registry.archiveCommand(command);
        registry.archiveSkill(skill);
        registry.archiveMcpServer(mcp);

        // Then
        assertEquals(command.getPromptTemplate(), registry.requireCommand(
                command.getIdentifier(), command.getVersion(),
                command.getContentHash()).getPromptTemplate());
        SkillPackage restoredSkill = registry.requireSkill(
                skill.getManifest().getId(), skill.getManifest().getVersion(),
                skill.getPackageHash());
        assertEquals(skill.getEntryContent(), restoredSkill.getEntryContent());
        assertEquals("exact rules", new String(
                restoredSkill.getResourceContents().get("references/rules.md"),
                StandardCharsets.UTF_8));
        McpServerDefinition restoredMcp = registry.requireMcpServer(
                mcp.getId(), mcp.getVersion(), mcp.getConfigurationHash());
        assertEquals(McpTransport.STREAMABLE_HTTP, restoredMcp.getTransport());
        assertEquals("https://mcp.example.test/api", restoredMcp.getEndpoint());
    }

    @Test
    void should_RejectSameIdentifierVersion_When_CommandContentHashDiffers() {
        // Given
        registry.archiveCommand(command("first prompt"));

        // When / Then
        CapabilityArtifactIntegrityException failure = assertThrows(
                CapabilityArtifactIntegrityException.class,
                () -> registry.archiveCommand(command("changed prompt")));
        assertEquals("WORKBENCH_CAPABILITY_ARTIFACT_CONTENT_CONFLICT",
                failure.getCode());
    }

    @Test
    void should_FailClosed_When_ArchivedSkillFileIsCorrupted() throws Exception {
        // Given
        SkillPackage skill = skillPackage();
        registry.archiveSkill(skill);
        String artifactKey = jdbcTemplate.queryForObject(
                "SELECT artifact_key FROM workbench_skill_package_revision "
                        + "WHERE skill_identifier = ? AND skill_version = ?",
                String.class, skill.getManifest().getId(),
                skill.getManifest().getVersion());
        Path entry = artifactRoot.resolve(artifactKey).resolve("SKILL.md");
        Files.writeString(entry, "corrupted", StandardCharsets.UTF_8);

        // When / Then
        CapabilityArtifactIntegrityException failure = assertThrows(
                CapabilityArtifactIntegrityException.class,
                () -> registry.requireSkill(
                        skill.getManifest().getId(), skill.getManifest().getVersion(),
                        skill.getPackageHash()));
        assertEquals("WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED",
                failure.getCode());
    }

    @Test
    void should_RejectSymbolicLink_When_ArchivedSkillPathIsReplaced() throws Exception {
        // Given
        SkillPackage skill = skillPackage();
        registry.archiveSkill(skill);
        String artifactKey = jdbcTemplate.queryForObject(
                "SELECT artifact_key FROM workbench_skill_package_revision "
                        + "WHERE skill_identifier = ? AND skill_version = ?",
                String.class, skill.getManifest().getId(),
                skill.getManifest().getVersion());
        Path entry = artifactRoot.resolve(artifactKey).resolve("SKILL.md");
        Path outside = Files.writeString(
                tempDirectory.resolve("outside.md"), "outside", StandardCharsets.UTF_8);
        Files.delete(entry);
        Files.createSymbolicLink(entry, outside);

        // When / Then
        CapabilityArtifactIntegrityException failure = assertThrows(
                CapabilityArtifactIntegrityException.class,
                () -> registry.requireSkill(
                        skill.getManifest().getId(), skill.getManifest().getVersion(),
                        skill.getPackageHash()));
        assertEquals("WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED",
                failure.getCode());
        assertTrue(Files.exists(outside));
    }

    @Test
    void should_FailArchive_When_OrphanedContentAddressedArtifactIsCorrupted()
            throws Exception {
        // Given
        SkillPackage skill = skillPackage();
        registry.archiveSkill(skill);
        String artifactKey = jdbcTemplate.queryForObject(
                "SELECT artifact_key FROM workbench_skill_package_revision "
                        + "WHERE skill_identifier = ? AND skill_version = ?",
                String.class, skill.getManifest().getId(),
                skill.getManifest().getVersion());
        jdbcTemplate.update("DELETE FROM workbench_skill_package_revision "
                + "WHERE skill_identifier = ? AND skill_version = ?",
                skill.getManifest().getId(), skill.getManifest().getVersion());
        Files.writeString(artifactRoot.resolve(artifactKey).resolve("SKILL.md"),
                "corrupted orphan", StandardCharsets.UTF_8);

        // When / Then
        assertThrows(CapabilityArtifactIntegrityException.class,
                () -> registry.archiveSkill(skill));
    }

    private void createTables() {
        jdbcTemplate.execute("CREATE TABLE workbench_command_definition_revision ("
                + "command_identifier TEXT NOT NULL, command_version TEXT NOT NULL,"
                + "definition_json TEXT NOT NULL, content_hash TEXT NOT NULL,"
                + "payload_hash TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "PRIMARY KEY(command_identifier, command_version))");
        jdbcTemplate.execute("CREATE TABLE workbench_skill_package_revision ("
                + "skill_identifier TEXT NOT NULL, skill_version TEXT NOT NULL,"
                + "manifest_json TEXT NOT NULL, manifest_hash TEXT NOT NULL,"
                + "package_hash TEXT NOT NULL,"
                + "artifact_key TEXT NOT NULL, artifact_size INTEGER NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "PRIMARY KEY(skill_identifier, skill_version))");
        jdbcTemplate.execute("CREATE TABLE workbench_mcp_server_definition_revision ("
                + "server_identifier TEXT NOT NULL, server_version TEXT NOT NULL,"
                + "definition_json TEXT NOT NULL, definition_hash TEXT NOT NULL,"
                + "payload_hash TEXT NOT NULL,"
                + "created_at INTEGER NOT NULL,"
                + "PRIMARY KEY(server_identifier, server_version))");
    }

    private CommandDefinition command(String prompt) {
        return CommandDefinition.create(
                "architecture-review", "1.0.0", "Architecture Review",
                "Review architecture", "<target>", prompt,
                "platform-commands", Instant.parse("2026-08-05T07:00:00Z"));
    }

    private SkillPackage skillPackage() {
        SkillManifest manifest = new SkillManifest(
                "domain-modeling-audit", "1.0.0", "Audit domain boundaries",
                set("SOLUTION_DESIGN"), set("java"), set("domain audit"),
                "SKILL.md", set("references/rules.md"),
                Collections.singletonList(new SkillDependency("base-skill", "1.0.0")),
                Collections.emptySet(), set("CODEX"), SkillTrustSource.PLATFORM,
                Collections.singletonList(new CapabilityRequest(
                        CapabilityKind.FILE, CapabilityAccess.READ, "repository")));
        List<CapabilityCatalogFiles.CatalogFile> files = List.of(
                new CapabilityCatalogFiles.CatalogFile(
                        "manifest.yml", "manifest facts".getBytes(StandardCharsets.UTF_8)),
                new CapabilityCatalogFiles.CatalogFile(
                        "SKILL.md", "# exact skill".getBytes(StandardCharsets.UTF_8)),
                new CapabilityCatalogFiles.CatalogFile(
                        "references/rules.md", "exact rules".getBytes(StandardCharsets.UTF_8)));
        Map<String, byte[]> resources = new LinkedHashMap<String, byte[]>();
        resources.put("references/rules.md",
                "exact rules".getBytes(StandardCharsets.UTF_8));
        return new SkillPackage(manifest, CapabilityCatalogFiles.packageHash(files),
                "# exact skill", CapabilityCatalogFiles.resourceHashes(files), resources);
    }

    private McpServerDefinition mcpServer() {
        return McpServerDefinition.managed(
                "remote-query", "1.0.0", "Remote Query", "Query repositories",
                set("CODEX"), Collections.emptyList(),
                Collections.singletonList(new McpSecretReference(
                        "REMOTE_TOKEN", "environment:REMOTE_TOKEN")),
                McpTransport.STREAMABLE_HTTP, "",
                "https://mcp.example.test/api", CapabilityAccess.READ,
                10, 30, com.example.agentweb.domain.shared.CanonicalHashing.sha256("mcp"));
    }

    private LinkedHashSet<String> set(String value) {
        return new LinkedHashSet<String>(Collections.singleton(value));
    }
}
