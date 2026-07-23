package com.example.agentweb.infra.setting;

import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.setting.WorkspaceSettingsRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 工作空间配置 SQLite 仓储。完整配置以一个 JSON 值原子保存到 app_setting。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Repository
public class SqliteWorkspaceSettingsRepository implements WorkspaceSettingsRepository {

    static final String SETTING_KEY = "workspace.configuration";

    private final AppSettingRepository appSettingRepository;
    private final ObjectMapper objectMapper;

    public SqliteWorkspaceSettingsRepository(AppSettingRepository appSettingRepository,
                                             ObjectMapper objectMapper) {
        this.appSettingRepository = appSettingRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<WorkspaceSettings> find() {
        return appSettingRepository.get(SETTING_KEY).map(this::deserialize);
    }

    @Override
    public void save(WorkspaceSettings settings) {
        WorkspaceSettingsDocument document = new WorkspaceSettingsDocument(
                settings.getDefaultWorkspace(), settings.getWorkspaceRoots(), settings.getUploadRoots());
        try {
            appSettingRepository.put(SETTING_KEY, objectMapper.writeValueAsString(document),
                    System.currentTimeMillis());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize workspace settings", ex);
        }
    }

    @Override
    public void delete() {
        appSettingRepository.delete(SETTING_KEY);
    }

    private WorkspaceSettings deserialize(String value) {
        try {
            WorkspaceSettingsDocument document = objectMapper.readValue(value, WorkspaceSettingsDocument.class);
            return WorkspaceSettings.create(document.defaultWorkspace,
                    document.workspaceRoots, document.uploadRoots);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("Stored workspace settings are invalid", ex);
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    private static class WorkspaceSettingsDocument {
        public String defaultWorkspace;
        public List<String> workspaceRoots;
        public List<String> uploadRoots;
    }
}
