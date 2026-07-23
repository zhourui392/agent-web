package com.example.agentweb.app.setting;

import com.example.agentweb.app.setting.port.WorkspaceSettingsSeedProvider;
import com.example.agentweb.domain.setting.WorkspaceSettings;
import com.example.agentweb.domain.setting.WorkspaceSettingsPolicy;
import com.example.agentweb.domain.setting.WorkspaceSettingsRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作空间运行设置应用服务。仅编排配置查询、校验和持久化。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
public class WorkspaceSettingsAppServiceImpl implements WorkspaceSettingsQueryService {

    private final WorkspaceSettingsRepository repository;
    private final WorkspaceSettingsPolicy policy;
    private final WorkspaceSettingsSeedProvider seedProvider;

    public WorkspaceSettingsAppServiceImpl(WorkspaceSettingsRepository repository,
                                           WorkspaceSettingsPolicy policy,
                                           WorkspaceSettingsSeedProvider seedProvider) {
        this.repository = repository;
        this.policy = policy;
        this.seedProvider = seedProvider;
    }

    @Override
    public WorkspaceSettings get() {
        return repository.find().orElseGet(seedProvider::get);
    }

    public void update(String defaultWorkspace,
                       List<String> workspaceRoots,
                       List<String> uploadRoots) {
        update(WorkspaceSettings.create(defaultWorkspace, workspaceRoots, uploadRoots));
    }

    public void update(WorkspaceSettings settings) {
        policy.requireUsable(settings);
        repository.save(settings);
    }

    public void reset() {
        repository.delete();
    }
}
