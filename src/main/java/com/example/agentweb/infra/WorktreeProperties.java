package com.example.agentweb.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * worktree 相关配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@ConfigurationProperties(prefix = "agent.worktree")
public class WorktreeProperties {

    /**
     * 允许作为 workspace 根的目录白名单(绝对路径)。worktree 操作的 workspacePath 必须落在某个根下。
     * <p>留空表示不限制(仅启动 WARN 提示加固), 兼容现有部署; 配置后即开启强校验, 越界返回 400。</p>
     */
    private List<String> allowedRoots = new ArrayList<>();

    public List<String> getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(List<String> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }
}
