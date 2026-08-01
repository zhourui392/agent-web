package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.util.Objects;

/**
 * 单次 Runtime 冻结的安全仓库身份与读写权限事实。
 *
 * <p>只包含逻辑仓库 key、相对路径和访问级别，不包含 Workspace Root、
 * 仓库绝对路径或文件系统身份。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RunRepositoryScopeFact {

    public enum Access {
        READ,
        WRITE
    }

    private final String repositoryKey;
    private final String relativePath;
    private final boolean primary;
    private final Access access;

    private RunRepositoryScopeFact(
            String repositoryKey, String relativePath,
            boolean primary, Access access) {
        this.repositoryKey = DocumentReference.requireRepositoryKey(
                repositoryKey);
        this.relativePath = DocumentReference.requireRelativePath(
                relativePath);
        this.primary = primary;
        this.access = Objects.requireNonNull(
                access, "run repository access");
    }

    static RunRepositoryScopeFact of(
            String repositoryKey, String relativePath,
            boolean primary, Access access) {
        return new RunRepositoryScopeFact(
                repositoryKey, relativePath, primary, access);
    }
}
