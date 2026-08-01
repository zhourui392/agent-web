package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Infrastructure 已完成真实路径、Git 仓库和入口检查后交给 Domain 的仓库事实。
 *
 * <p>本值对象不访问文件系统。{@code repositoryRoot} 必须是 Infrastructure 通过
 * {@code toRealPath()} 得到的绝对规范路径，{@code entrySymbolicLink} 是入口检查事实。
 * Domain 仍会拒绝明显不可信或不一致的事实，最终授权边界由 {@link RepositoryScope}
 * 统一建立。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ResolvedRepository {

    private final String repositoryKey;
    private final String relativePath;
    private final String repositoryRoot;
    private final String rootFingerprint;
    private final boolean entrySymbolicLink;

    private ResolvedRepository(String repositoryKey, String repositoryRoot,
                               String rootFingerprint, boolean entrySymbolicLink) {
        this.repositoryKey = RepositorySelection.normalizeRepositoryKey(repositoryKey);
        this.relativePath = this.repositoryKey;
        this.repositoryRoot = requireNormalizedAbsolutePath(
                repositoryRoot, "repository real root").toString();
        this.rootFingerprint = DomainText.requireSha256(
                rootFingerprint, "repository root fingerprint");
        if (entrySymbolicLink) {
            throw new IllegalArgumentException(
                    "repository entry must not be a symbolic link: " + this.repositoryKey);
        }
        this.entrySymbolicLink = false;
    }

    /**
     * 从 Infrastructure 已验证的文件系统事实建立不可变仓库引用。
     *
     * @param repositoryKey    Workspace Root 下的规范相对仓库 key
     * @param repositoryRoot   已解析的仓库真实绝对路径
     * @param rootFingerprint  仓库根身份的稳定 SHA-256
     * @param entrySymbolicLink 仓库入口是否为符号链接的检查事实
     * @return 已验证事实值对象
     */
    public static ResolvedRepository fromVerifiedFacts(
            String repositoryKey, String repositoryRoot, String rootFingerprint,
            boolean entrySymbolicLink) {
        return new ResolvedRepository(repositoryKey, repositoryRoot, rootFingerprint,
                entrySymbolicLink);
    }

    Path repositoryRootPath() {
        return Paths.get(repositoryRoot);
    }

    static Path requireNormalizedAbsolutePath(String value, String name) {
        String required = DomainText.require(value, name, 4096);
        try {
            Path path = Paths.get(required);
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(name + " must be absolute");
            }
            Path normalized = path.normalize();
            if (!path.equals(normalized)) {
                throw new IllegalArgumentException(name + " must already be normalized");
            }
            return normalized;
        } catch (InvalidPathException ex) {
            throw new IllegalArgumentException(name + " is invalid", ex);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ResolvedRepository)) {
            return false;
        }
        ResolvedRepository that = (ResolvedRepository) other;
        return repositoryKey.equals(that.repositoryKey)
                && relativePath.equals(that.relativePath)
                && repositoryRoot.equals(that.repositoryRoot)
                && rootFingerprint.equals(that.rootFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryKey, relativePath, repositoryRoot, rootFingerprint);
    }
}
