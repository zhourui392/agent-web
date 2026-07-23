package com.example.agentweb.config;

import com.example.agentweb.app.setting.port.WorkspaceSettingsSeedProvider;
import com.example.agentweb.domain.setting.WorkspaceDirectoryProbe;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.setting.WorkspaceSettingsPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作空间运行配置装配。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Configuration
public class WorkspaceSettingsConfig {

    @Bean
    public WorkspaceSettingsPolicy workspaceSettingsPolicy(WorkspaceDirectoryProbe directoryProbe) {
        return new WorkspaceSettingsPolicy(directoryProbe);
    }

    @Bean
    public WorkspaceSettingsSeedProvider workspaceSettingsSeedProvider(FsProperties properties) {
        return () -> seed(properties);
    }

    private WorkspaceSettings seed(FsProperties properties) {
        List<String> workspaceRoots = new ArrayList<String>(properties.getRoots());
        List<String> uploadRoots = new ArrayList<String>(properties.getUploadRoots());
        String defaultWorkspace = workspaceRoots.isEmpty() ? null : workspaceRoots.get(0);
        return WorkspaceSettings.create(defaultWorkspace, workspaceRoots, uploadRoots);
    }
}
