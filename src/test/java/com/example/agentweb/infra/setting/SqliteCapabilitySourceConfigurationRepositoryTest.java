package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.capability.CapabilityConfigurationEditor;
import com.example.agentweb.domain.capability.CapabilitySourceConfiguration;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import com.example.agentweb.domain.capability.CommandCatalogDirectory;
import com.example.agentweb.domain.capability.SkillCatalogDirectory;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Capability Source Configuration SQLite 仓储测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class SqliteCapabilitySourceConfigurationRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteCapabilitySourceConfigurationRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("capability-source.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE workbench_capability_source_configuration ("
                + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),"
                + "command_directories_json TEXT NOT NULL,"
                + "skill_directories_json TEXT NOT NULL,"
                + "mcp_configuration_json TEXT NOT NULL,"
                + "configuration_hash TEXT NOT NULL,"
                + "updated_by_id TEXT NOT NULL,"
                + "updated_by_name TEXT NOT NULL,"
                + "updated_at INTEGER NOT NULL,"
                + "version INTEGER NOT NULL CHECK(version >= 1))");
        repository = new SqliteCapabilitySourceConfigurationRepository(
                jdbc, new ObjectMapper());
    }

    @Test
    void should_ReturnEmpty_When_ConfigurationDoesNotExist() {
        // Given / When / Then
        assertFalse(repository.find().isPresent());
    }

    @Test
    void should_RoundTripConfiguration_When_FirstVersionIsSaved() {
        // Given
        CapabilitySourceConfiguration configuration = buildConfiguration();

        // When
        repository.save(configuration, 0L);
        CapabilitySourceConfiguration restored = repository.find().orElseThrow();

        // Then
        assertEquals(configuration.getConfigurationHash(), restored.getConfigurationHash());
        assertEquals(configuration.getCommandCatalogDirectories().get(0).getAbsoluteDirectory(),
                restored.getCommandCatalogDirectories().get(0).getAbsoluteDirectory());
        assertEquals(SkillTrustSource.PLATFORM,
                restored.getSkillCatalogDirectories().get(0).getTrustSource());
    }

    @Test
    void should_UpdateWithCompareAndSet_When_VersionMatches() {
        // Given
        CapabilitySourceConfiguration first = buildConfiguration();
        repository.save(first, 0L);
        CapabilitySourceConfiguration second = first.update(1L,
                first.getCommandCatalogDirectories(), first.getSkillCatalogDirectories(),
                emptyMcp(), CapabilityConfigurationEditor.create("admin-2", "Blair"),
                Instant.parse("2026-08-05T09:00:00Z"));

        // When
        repository.save(second, 1L);

        // Then
        assertEquals(2L, repository.find().orElseThrow().getVersion());
        assertEquals("admin-2", repository.find().orElseThrow().getUpdatedBy().getActorId());
    }

    @Test
    void should_FailClosed_When_VersionIsStaleOrHashIsCorrupted() {
        // Given
        CapabilitySourceConfiguration first = buildConfiguration();
        repository.save(first, 0L);

        // When / Then
        assertThrows(CapabilitySourceVersionConflictException.class,
                () -> repository.save(first, 1L));
        jdbc.update("UPDATE workbench_capability_source_configuration "
                + "SET configuration_hash = ? WHERE singleton_id = 1", "0".repeat(64));
        assertThrows(IllegalStateException.class, repository::find);
    }

    private CapabilitySourceConfiguration buildConfiguration() {
        return CapabilitySourceConfiguration.create(
                Collections.singletonList(CommandCatalogDirectory.create(
                        "commands", "/opt/agent/commands", true)),
                Collections.singletonList(SkillCatalogDirectory.create(
                        "skills", "/opt/agent/skills", SkillTrustSource.PLATFORM, true)),
                emptyMcp(), CapabilityConfigurationEditor.create("admin-1", "Alex"),
                Instant.parse("2026-08-05T08:00:00Z"));
    }

    private String emptyMcp() {
        return "{\"schema\":\"workbench-mcp-catalog@1\",\"servers\":[]}";
    }
}
