package com.example.agentweb.domain.setting;

/**
 * 工作空间配置可用性策略。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class WorkspaceSettingsPolicy {

    private final WorkspaceDirectoryProbe directoryProbe;

    public WorkspaceSettingsPolicy(WorkspaceDirectoryProbe directoryProbe) {
        this.directoryProbe = directoryProbe;
    }

    /**
     * 要求配置中的授权根在保存时均为真实可访问目录。
     *
     * @param settings 待保存配置
     */
    public void requireUsable(WorkspaceSettings settings) {
        for (String root : settings.getWorkspaceRoots()) {
            requireExistingDirectory(root);
        }
        for (String root : settings.getUploadRoots()) {
            requireExistingDirectory(root);
        }
    }

    private void requireExistingDirectory(String path) {
        if (!directoryProbe.isExistingDirectory(path)) {
            throw new IllegalArgumentException("Configured path is not an existing directory: " + path);
        }
    }
}
