package com.example.agentweb.app.setting.port;

import com.example.agentweb.domain.setting.WorkspaceSettings;

/**
 * 数据库尚未配置时的工作空间种子配置端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public interface WorkspaceSettingsSeedProvider {

    /**
     * 读取配置文件提供的工作空间种子。
     *
     * @return 合法的种子配置
     */
    WorkspaceSettings get();
}
