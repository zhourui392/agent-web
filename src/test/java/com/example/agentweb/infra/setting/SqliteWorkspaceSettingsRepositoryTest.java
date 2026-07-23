package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工作空间设置 SQLite 仓储测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class SqliteWorkspaceSettingsRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkspaceSettingsRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("test.db"));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE app_setting ("
                + "setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at INTEGER NOT NULL)");
        repository = new SqliteWorkspaceSettingsRepository(
                new SqliteAppSettingRepo(jdbc), new ObjectMapper());
    }

    @Test
    void save_thenFind_shouldRoundTripOneAtomicConfigurationDocument() {
        WorkspaceSettings settings = WorkspaceSettings.create(
                "/srv/project",
                Arrays.asList("/srv/workspace", "/srv/project"),
                Collections.singletonList("/srv/upload")
        );

        repository.save(settings);

        WorkspaceSettings restored = repository.find().orElse(null);
        assertEquals(settings.getDefaultWorkspace(), restored.getDefaultWorkspace());
        assertEquals(settings.getWorkspaceRoots(), restored.getWorkspaceRoots());
        assertEquals(settings.getUploadRoots(), restored.getUploadRoots());
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM app_setting WHERE setting_key = ?",
                Long.class, SqliteWorkspaceSettingsRepository.SETTING_KEY);
        assertEquals(1L, count);
    }

    @Test
    void delete_shouldRemoveConfigurationAndInvalidateCachedDocument() {
        WorkspaceSettings first = WorkspaceSettings.create("/srv/first",
                Collections.singletonList("/srv/first"), Collections.<String>emptyList());
        WorkspaceSettings second = WorkspaceSettings.create("/srv/second",
                Collections.singletonList("/srv/second"), Collections.<String>emptyList());
        repository.save(first);
        assertTrue(repository.find().isPresent());

        repository.delete();
        assertFalse(repository.find().isPresent());
        repository.save(second);

        assertEquals("/srv/second", repository.find().get().getDefaultWorkspace());
    }

    @Test
    void find_invalidStoredDocument_shouldFailClosed() {
        jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_at) VALUES (?, ?, ?)",
                SqliteWorkspaceSettingsRepository.SETTING_KEY, "{not-json", 1000L);

        assertThrows(IllegalStateException.class, repository::find);
    }
}
