package com.example.agentweb.app.setting;

import com.example.agentweb.domain.setting.WorkspaceSettings;

/**
 * 工作空间运行配置读侧接口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface WorkspaceSettingsQueryService {

    /**
     * 读取当前生效的工作空间配置。
     *
     * @return 数据库配置，未配置时返回配置文件种子
     */
    WorkspaceSettings get();
}
