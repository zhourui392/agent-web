package com.example.agentweb.app.workbench.port;

import lombok.Getter;

/**
 * Infrastructure 反解绝对观察路径后返回的结构化文件身份。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceFileReference {

    private final String repositoryKey;
    private final String relativePath;

    public WorkspaceFileReference(String repositoryKey, String relativePath) {
        this.repositoryKey = repositoryKey;
        this.relativePath = relativePath;
    }
}
