package com.example.agentweb.domain.worktree;

/**
 * 工作空间文件系统边界端口。调用方只能使用经过允许根目录和真实路径校验的路径。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface WorkspacePathPolicy {

    String requireExistingDirectory(String path);

    String requireExistingFile(String path);

    String prepareWorkspaceDirectory(String path);

    String prepareUploadDirectory(String path);

    boolean isExistingPathAllowed(String path);
}
