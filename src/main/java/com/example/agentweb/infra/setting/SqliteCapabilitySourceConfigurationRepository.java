package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceConfigurationRepository;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Capability Source Configuration 的 SQLite 单例仓储。
 *
 * @author alex
 * @since 2026-08-05
 */
@Repository
public class SqliteCapabilitySourceConfigurationRepository
        implements CapabilitySourceConfigurationRepository {

    private static final int SINGLETON_ID = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteCapabilitySourceConfigurationRepository(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        if (jdbcTemplate == null || objectMapper == null) {
            throw new IllegalArgumentException(
                    "capability source repository dependencies are required");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<CapabilitySourceConfiguration> find() {
        List<CapabilitySourceConfiguration> rows = jdbcTemplate.query(
                "SELECT command_directories_json, skill_directories_json, "
                        + "mcp_configuration_json, configuration_hash, updated_by_id, "
                        + "updated_by_name, updated_at, version "
                        + "FROM workbench_capability_source_configuration "
                        + "WHERE singleton_id = ?",
                (resultSet, rowNumber) -> CapabilitySourceConfiguration.restore(
                        commandDirectories(resultSet.getString("command_directories_json")),
                        skillDirectories(resultSet.getString("skill_directories_json")),
                        resultSet.getString("mcp_configuration_json"),
                        resultSet.getString("configuration_hash"),
                        CapabilityConfigurationEditor.create(
                                resultSet.getString("updated_by_id"),
                                resultSet.getString("updated_by_name")),
                        Instant.ofEpochMilli(resultSet.getLong("updated_at")),
                        resultSet.getLong("version")),
                SINGLETON_ID);
        return rows.stream().findFirst();
    }

    @Override
    public void save(
            CapabilitySourceConfiguration configuration, long expectedVersion) {
        if (configuration == null) {
            throw new IllegalArgumentException("capability source configuration is required");
        }
        if (configuration.getVersion() != expectedVersion + 1L) {
            throw new CapabilitySourceVersionConflictException(
                    expectedVersion, actualVersion());
        }
        SerializedConfiguration serialized = serialize(configuration);
        int changed;
        if (expectedVersion == 0L) {
            changed = jdbcTemplate.update(
                    "INSERT OR IGNORE INTO workbench_capability_source_configuration "
                            + "(singleton_id, command_directories_json, skill_directories_json, "
                            + "mcp_configuration_json, configuration_hash, updated_by_id, "
                            + "updated_by_name, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    SINGLETON_ID, serialized.commandDirectoriesJson,
                    serialized.skillDirectoriesJson, configuration.getMcpConfigurationJson(),
                    configuration.getConfigurationHash(),
                    configuration.getUpdatedBy().getActorId(),
                    configuration.getUpdatedBy().getActorName(),
                    configuration.getUpdatedAt().toEpochMilli(), configuration.getVersion());
        } else {
            changed = jdbcTemplate.update(
                    "UPDATE workbench_capability_source_configuration SET "
                            + "command_directories_json = ?, skill_directories_json = ?, "
                            + "mcp_configuration_json = ?, configuration_hash = ?, "
                            + "updated_by_id = ?, updated_by_name = ?, updated_at = ?, version = ? "
                            + "WHERE singleton_id = ? AND version = ?",
                    serialized.commandDirectoriesJson, serialized.skillDirectoriesJson,
                    configuration.getMcpConfigurationJson(), configuration.getConfigurationHash(),
                    configuration.getUpdatedBy().getActorId(),
                    configuration.getUpdatedBy().getActorName(),
                    configuration.getUpdatedAt().toEpochMilli(), configuration.getVersion(),
                    SINGLETON_ID, expectedVersion);
        }
        if (changed != 1) {
            throw new CapabilitySourceVersionConflictException(
                    expectedVersion, actualVersion());
        }
    }

    private long actualVersion() {
        Long actual = jdbcTemplate.query(
                "SELECT version FROM workbench_capability_source_configuration "
                        + "WHERE singleton_id = ?",
                resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L,
                SINGLETON_ID);
        return actual == null ? 0L : actual.longValue();
    }

    private SerializedConfiguration serialize(CapabilitySourceConfiguration configuration) {
        List<CommandDirectoryDocument> commands = new ArrayList<CommandDirectoryDocument>();
        for (CommandCatalogDirectory directory : configuration.getCommandCatalogDirectories()) {
            commands.add(new CommandDirectoryDocument(
                    directory.getDirectoryIdentifier(), directory.getAbsoluteDirectory(),
                    directory.isEnabled()));
        }
        List<SkillDirectoryDocument> skills = new ArrayList<SkillDirectoryDocument>();
        for (SkillCatalogDirectory directory : configuration.getSkillCatalogDirectories()) {
            skills.add(new SkillDirectoryDocument(
                    directory.getDirectoryIdentifier(), directory.getAbsoluteDirectory(),
                    directory.getTrustSource().name(), directory.isEnabled()));
        }
        try {
            return new SerializedConfiguration(
                    objectMapper.writeValueAsString(commands),
                    objectMapper.writeValueAsString(skills));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "cannot serialize capability source configuration", failure);
        }
    }

    private List<CommandCatalogDirectory> commandDirectories(String json) {
        try {
            List<CommandDirectoryDocument> documents = objectMapper.readValue(
                    json, new TypeReference<List<CommandDirectoryDocument>>() { });
            List<CommandCatalogDirectory> directories =
                    new ArrayList<CommandCatalogDirectory>();
            for (CommandDirectoryDocument document : documents) {
                directories.add(CommandCatalogDirectory.create(
                        document.directoryIdentifier, document.absoluteDirectory,
                        document.enabled));
            }
            return Collections.unmodifiableList(directories);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "cannot restore command catalog directories", failure);
        }
    }

    private List<SkillCatalogDirectory> skillDirectories(String json) {
        try {
            List<SkillDirectoryDocument> documents = objectMapper.readValue(
                    json, new TypeReference<List<SkillDirectoryDocument>>() { });
            List<SkillCatalogDirectory> directories = new ArrayList<SkillCatalogDirectory>();
            for (SkillDirectoryDocument document : documents) {
                directories.add(SkillCatalogDirectory.create(
                        document.directoryIdentifier, document.absoluteDirectory,
                        SkillTrustSource.valueOf(document.trustSource), document.enabled));
            }
            return Collections.unmodifiableList(directories);
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("cannot restore skill catalog directories", failure);
        }
    }

    private static final class SerializedConfiguration {
        private final String commandDirectoriesJson;
        private final String skillDirectoriesJson;

        private SerializedConfiguration(
                String commandDirectoriesJson, String skillDirectoriesJson) {
            this.commandDirectoriesJson = commandDirectoriesJson;
            this.skillDirectoriesJson = skillDirectoriesJson;
        }
    }

    private static final class CommandDirectoryDocument {
        public String directoryIdentifier;
        public String absoluteDirectory;
        public boolean enabled;

        public CommandDirectoryDocument() {
        }

        private CommandDirectoryDocument(
                String directoryIdentifier, String absoluteDirectory, boolean enabled) {
            this.directoryIdentifier = directoryIdentifier;
            this.absoluteDirectory = absoluteDirectory;
            this.enabled = enabled;
        }
    }

    private static final class SkillDirectoryDocument {
        public String directoryIdentifier;
        public String absoluteDirectory;
        public String trustSource;
        public boolean enabled;

        public SkillDirectoryDocument() {
        }

        private SkillDirectoryDocument(String directoryIdentifier, String absoluteDirectory,
                                       String trustSource, boolean enabled) {
            this.directoryIdentifier = directoryIdentifier;
            this.absoluteDirectory = absoluteDirectory;
            this.trustSource = trustSource;
            this.enabled = enabled;
        }
    }
}
