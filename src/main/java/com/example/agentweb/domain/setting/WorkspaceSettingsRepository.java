package com.example.agentweb.domain.setting;

import java.util.Optional;

/**
 * 工作空间配置生命周期仓储。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface WorkspaceSettingsRepository {

    /**
     * 查找已落库的工作空间配置。
     *
     * @return 尚未配置时为空
     */
    Optional<WorkspaceSettings> find();

    /**
     * 保存完整工作空间配置。
     *
     * @param settings 合法配置
     */
    void save(WorkspaceSettings settings);

    /** 删除已落库配置，使读取方恢复配置文件种子。 */
    void delete();
}
