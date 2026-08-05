package com.example.agentweb.infra.capability;

import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.capability.CommandDefinition;
import com.example.agentweb.domain.capability.McpServerDefinition;
import com.example.agentweb.domain.capability.SkillManifest;
import com.example.agentweb.domain.capability.SkillPackage;
import com.example.agentweb.domain.shared.CanonicalHashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * SQLite 元数据与内容寻址文件存储组合的 Capability Artifact Registry。
 *
 * @author alex
 * @since 2026-08-05
 */
public final class SqliteCapabilityArtifactRegistry
        implements CapabilityArtifactRegistry {

    private final JdbcTemplate jdbcTemplate;
    private final CapabilityArtifactDocumentMapper documentMapper;
    private final ContentAddressedSkillArtifactStore skillArtifactStore;
    private final Clock clock;

    public SqliteCapabilityArtifactRegistry(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            Path artifactRoot, Clock clock) {
        if (jdbcTemplate == null || clock == null) {
            throw new IllegalArgumentException(
                    "Capability Artifact Registry dependencies are required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.documentMapper = new CapabilityArtifactDocumentMapper(objectMapper);
        this.skillArtifactStore = new ContentAddressedSkillArtifactStore(artifactRoot);
        this.clock = clock;
    }

    @Override
    public void archiveCommand(CommandDefinition definition) {
        CapabilityArtifactDocumentMapper.SerializedDocument serialized =
                documentMapper.command(definition);
        int inserted = jdbcTemplate.update(
                "INSERT OR IGNORE INTO workbench_command_definition_revision "
                        + "(command_identifier, command_version, definition_json, "
                        + "content_hash, payload_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                definition.getIdentifier(), definition.getVersion(), serialized.getJson(),
                definition.getContentHash(), serialized.getPayloadHash(),
                clock.instant().toEpochMilli());
        if (inserted == 0) {
            requireCommand(definition.getIdentifier(), definition.getVersion(),
                    definition.getContentHash());
        }
    }

    @Override
    public void archiveSkill(SkillPackage skillPackage) {
        List<SkillRow> existing = skillRows(
                skillPackage.getManifest().getId(),
                skillPackage.getManifest().getVersion());
        if (!existing.isEmpty()) {
            requireMatchingHash(existing.get(0).packageHash,
                    skillPackage.getPackageHash());
            requireSkill(skillPackage.getManifest().getId(),
                    skillPackage.getManifest().getVersion(),
                    skillPackage.getPackageHash());
            return;
        }
        CapabilityArtifactDocumentMapper.SerializedDocument manifest =
                documentMapper.skill(skillPackage);
        ContentAddressedSkillArtifactStore.StoredSkillArtifact stored =
                skillArtifactStore.archive(skillPackage);
        int inserted = jdbcTemplate.update(
                "INSERT OR IGNORE INTO workbench_skill_package_revision "
                        + "(skill_identifier, skill_version, manifest_json, manifest_hash, "
                        + "package_hash, artifact_key, artifact_size, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                skillPackage.getManifest().getId(),
                skillPackage.getManifest().getVersion(), manifest.getJson(),
                manifest.getPayloadHash(), skillPackage.getPackageHash(),
                stored.getArtifactKey(), stored.getArtifactSize(),
                clock.instant().toEpochMilli());
        if (inserted == 0) {
            requireSkill(skillPackage.getManifest().getId(),
                    skillPackage.getManifest().getVersion(),
                    skillPackage.getPackageHash());
        }
    }

    @Override
    public void archiveMcpServer(McpServerDefinition definition) {
        CapabilityArtifactDocumentMapper.SerializedDocument serialized =
                documentMapper.mcp(definition);
        int inserted = jdbcTemplate.update(
                "INSERT OR IGNORE INTO workbench_mcp_server_definition_revision "
                        + "(server_identifier, server_version, definition_json, "
                        + "definition_hash, payload_hash, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                definition.getId(), definition.getVersion(), serialized.getJson(),
                definition.getConfigurationHash(), serialized.getPayloadHash(),
                clock.instant().toEpochMilli());
        if (inserted == 0) {
            requireMcpServer(definition.getId(), definition.getVersion(),
                    definition.getConfigurationHash());
        }
    }

    @Override
    public CommandDefinition requireCommand(
            String identifier, String version, String expectedContentHash) {
        List<CommandRow> rows = jdbcTemplate.query(
                "SELECT definition_json, content_hash, payload_hash "
                        + "FROM workbench_command_definition_revision "
                        + "WHERE command_identifier = ? AND command_version = ?",
                (resultSet, rowNumber) -> new CommandRow(
                        resultSet.getString("definition_json"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("payload_hash")),
                identifier, version);
        if (rows.isEmpty()) {
            throw integrity("Command Artifact is missing");
        }
        CommandRow row = rows.get(0);
        requireMatchingHash(row.contentHash, expectedContentHash);
        requirePayloadHash(row.definitionJson, row.payloadHash);
        try {
            CommandDefinition restored = documentMapper.command(row.definitionJson);
            requireMatchingHash(restored.getContentHash(), row.contentHash);
            return restored;
        } catch (RuntimeException failure) {
            throw corrupted("Command Artifact cannot be restored", failure);
        }
    }

    @Override
    public SkillPackage requireSkill(
            String identifier, String version, String expectedPackageHash) {
        List<SkillRow> rows = skillRows(identifier, version);
        if (rows.isEmpty()) {
            throw integrity("Skill Artifact is missing");
        }
        SkillRow row = rows.get(0);
        requireMatchingHash(row.packageHash, expectedPackageHash);
        requirePayloadHash(row.manifestJson, row.manifestHash);
        try {
            SkillManifest manifest = documentMapper.skillManifest(row.manifestJson);
            ContentAddressedSkillArtifactStore.SkillArtifactContent content =
                    skillArtifactStore.read(row.artifactKey, manifest);
            if (content.getTotalBytes() != row.artifactSize) {
                throw integrity("Skill Artifact size does not match metadata");
            }
            SkillPackage restored = documentMapper.skill(
                    row.manifestJson, row.packageHash, content);
            requireMatchingHash(restored.getPackageHash(), row.packageHash);
            return restored;
        } catch (RuntimeException failure) {
            throw corrupted("Skill Artifact cannot be restored", failure);
        }
    }

    @Override
    public McpServerDefinition requireMcpServer(
            String identifier, String version, String expectedDefinitionHash) {
        List<McpRow> rows = jdbcTemplate.query(
                "SELECT definition_json, definition_hash, payload_hash "
                        + "FROM workbench_mcp_server_definition_revision "
                        + "WHERE server_identifier = ? AND server_version = ?",
                (resultSet, rowNumber) -> new McpRow(
                        resultSet.getString("definition_json"),
                        resultSet.getString("definition_hash"),
                        resultSet.getString("payload_hash")),
                identifier, version);
        if (rows.isEmpty()) {
            throw integrity("MCP Server Artifact is missing");
        }
        McpRow row = rows.get(0);
        requireMatchingHash(row.definitionHash, expectedDefinitionHash);
        requirePayloadHash(row.definitionJson, row.payloadHash);
        try {
            McpServerDefinition restored = documentMapper.mcp(row.definitionJson);
            requireMatchingHash(restored.getConfigurationHash(), row.definitionHash);
            return restored;
        } catch (RuntimeException failure) {
            throw corrupted("MCP Server Artifact cannot be restored", failure);
        }
    }

    private List<SkillRow> skillRows(String identifier, String version) {
        return jdbcTemplate.query(
                "SELECT manifest_json, manifest_hash, package_hash, artifact_key, "
                        + "artifact_size FROM workbench_skill_package_revision "
                        + "WHERE skill_identifier = ? AND skill_version = ?",
                (resultSet, rowNumber) -> new SkillRow(
                        resultSet.getString("manifest_json"),
                        resultSet.getString("manifest_hash"),
                        resultSet.getString("package_hash"),
                        resultSet.getString("artifact_key"),
                        resultSet.getLong("artifact_size")),
                identifier, version);
    }

    private void requirePayloadHash(String payload, String expectedHash) {
        String actual = CanonicalHashing.sha256(payload);
        if (!actual.equals(expectedHash)) {
            throw integrity("Capability Artifact payload Hash is corrupted");
        }
    }

    private void requireMatchingHash(String actual, String expected) {
        if (actual == null || expected == null || !actual.equals(expected)) {
            throw new CapabilityArtifactIntegrityException(
                    "WORKBENCH_CAPABILITY_ARTIFACT_CONTENT_CONFLICT",
                    "Capability identifier and version resolve to different content");
        }
    }

    private CapabilityArtifactIntegrityException integrity(String message) {
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message);
    }

    private CapabilityArtifactIntegrityException corrupted(
            String message, RuntimeException failure) {
        if (failure instanceof CapabilityArtifactIntegrityException
                && "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED".equals(
                ((CapabilityArtifactIntegrityException) failure).getCode())) {
            return (CapabilityArtifactIntegrityException) failure;
        }
        return new CapabilityArtifactIntegrityException(
                "WORKBENCH_CAPABILITY_ARTIFACT_INTEGRITY_FAILED", message, failure);
    }

    private static final class CommandRow {
        private final String definitionJson;
        private final String contentHash;
        private final String payloadHash;

        private CommandRow(
                String definitionJson, String contentHash, String payloadHash) {
            this.definitionJson = definitionJson;
            this.contentHash = contentHash;
            this.payloadHash = payloadHash;
        }
    }

    private static final class SkillRow {
        private final String manifestJson;
        private final String manifestHash;
        private final String packageHash;
        private final String artifactKey;
        private final long artifactSize;

        private SkillRow(
                String manifestJson, String manifestHash, String packageHash,
                String artifactKey, long artifactSize) {
            this.manifestJson = manifestJson;
            this.manifestHash = manifestHash;
            this.packageHash = packageHash;
            this.artifactKey = artifactKey;
            this.artifactSize = artifactSize;
        }
    }

    private static final class McpRow {
        private final String definitionJson;
        private final String definitionHash;
        private final String payloadHash;

        private McpRow(
                String definitionJson, String definitionHash, String payloadHash) {
            this.definitionJson = definitionJson;
            this.definitionHash = definitionHash;
            this.payloadHash = payloadHash;
        }
    }
}
