package com.example.agentweb.domain.setting;

/**
 * 工作空间目录状态探针。领域策略只依赖该端口，不依赖文件系统实现。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@FunctionalInterface
public interface WorkspaceDirectoryProbe {

    /**
     * 判断路径是否是当前可访问的目录。
     *
     * @param path 绝对路径
     * @return 可访问目录返回 true
     */
    boolean isExistingDirectory(String path);
}
