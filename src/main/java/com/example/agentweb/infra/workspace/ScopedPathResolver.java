package com.example.agentweb.infra.workspace;

import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.port.WorkspaceFileReference;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Repository Scope 内结构化路径与真实绝对观察路径的双向解析器。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
public class ScopedPathResolver {

    private static final int MAXIMUM_PATH_LENGTH = 4096;

    /**
     * 将客户端结构化文件身份解析为已存在且不含符号链接的真实路径。
     *
     * @param scope 已冻结的仓库授权范围
     * @param repositoryKey 客户端提交的仓库 Key
     * @param relativePath 客户端提交的 POSIX 相对路径
     * @return 已验证真实路径
     */
    public Path resolveExisting(RepositoryScope scope, String repositoryKey,
                                String relativePath) {
        if (scope == null) {
            throw scopeViolation("repository scope is required", null);
        }
        ResolvedRepository repository = requireRepository(scope, repositoryKey);
        Path root = requireCurrentRoot(repository);
        Path relative = relativePath(relativePath);
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw scopeViolation("document path escapes its selected repository", null);
        }
        rejectSymbolicLinks(root, candidate);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw scopeViolation("document path escapes its selected repository", null);
            }
            return real;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw scopeViolation("document path is not an accessible scoped path", ex);
        }
    }

    /**
     * 解析目录查询；仅精确空串可表示已选仓库根，其他路径继续使用严格 POSIX 规则。
     *
     * @param scope 已冻结的仓库授权范围
     * @param repositoryKey 客户端提交的仓库 Key
     * @param relativePath 精确空串或非空 POSIX 相对目录
     * @return 已验证的真实目录
     */
    public Path resolveDirectory(RepositoryScope scope, String repositoryKey,
                                 String relativePath) {
        if (scope == null) {
            throw scopeViolation("repository scope is required", null);
        }
        if (relativePath == null) {
            throw scopeViolation("document directory path is required", null);
        }
        Path resolved;
        if (relativePath.isEmpty()) {
            resolved = requireCurrentRoot(requireRepository(scope, repositoryKey));
        } else {
            resolved = resolveExisting(scope, repositoryKey, relativePath);
        }
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw scopeViolation("scoped document path is not a directory", null);
        }
        return resolved;
    }

    /**
     * 将 Runtime 观察到的已存在绝对路径反解为 Repository Key + 相对路径。
     *
     * @param scope 已冻结的仓库授权范围
     * @param absolutePath Runtime 观察到的绝对路径
     * @return 不含绝对路径的结构化文件身份
     */
    public WorkspaceFileReference identifyExisting(RepositoryScope scope, String absolutePath) {
        if (scope == null) {
            throw scopeViolation("repository scope is required", null);
        }
        Path candidate = absolutePath(absolutePath);
        ResolvedRepository matched = null;
        for (ResolvedRepository repository : scope.getRepositories()) {
            Path storedRoot = Paths.get(repository.getRepositoryRoot());
            if (candidate.startsWith(storedRoot)) {
                matched = repository;
                break;
            }
        }
        if (matched == null) {
            throw scopeViolation("observed path is outside selected repositories", null);
        }
        Path root = requireCurrentRoot(matched);
        rejectSymbolicLinks(root, candidate);
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root) || real.equals(root)) {
                throw scopeViolation("observed path is outside selected repository files", null);
            }
            String relative = portable(root.relativize(real));
            return new WorkspaceFileReference(matched.getRepositoryKey(), relative);
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw scopeViolation("observed path is not an accessible scoped path", ex);
        }
    }

    private ResolvedRepository requireRepository(RepositoryScope scope, String repositoryKey) {
        try {
            return scope.requireRepository(repositoryKey);
        } catch (IllegalArgumentException ex) {
            throw scopeViolation("repository is outside the selected repository scope", ex);
        }
    }

    private Path requireCurrentRoot(ResolvedRepository repository) {
        try {
            Path stored = Paths.get(repository.getRepositoryRoot());
            if (Files.isSymbolicLink(stored)) {
                throw topologyChanged(null);
            }
            Path real = stored.toRealPath();
            String fingerprint = WorkspaceFileSystemSecurity.rootFingerprint(real);
            if (!real.equals(stored) || !fingerprint.equals(repository.getRootFingerprint())) {
                throw topologyChanged(null);
            }
            return real;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (IOException ex) {
            throw topologyChanged(ex);
        }
    }

    private Path relativePath(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > MAXIMUM_PATH_LENGTH
                || value.indexOf('\\') >= 0 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*") || containsControlCharacter(value)) {
            throw scopeViolation("document path must be a bounded POSIX relative path", null);
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw scopeViolation(
                        "document path must not contain empty, '.' or '..' segments", null);
            }
        }
        try {
            Path relative = Paths.get(value);
            if (relative.isAbsolute()) {
                throw scopeViolation("document path must be relative", null);
            }
            return relative;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw scopeViolation("document path is invalid", ex);
        }
    }

    private Path absolutePath(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > MAXIMUM_PATH_LENGTH
                || containsControlCharacter(value)) {
            throw scopeViolation("observed path must be a bounded absolute path", null);
        }
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute() || !path.equals(path.normalize())) {
                throw scopeViolation("observed path must be normalized and absolute", null);
            }
            return path;
        } catch (WorkspaceOperationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw scopeViolation("observed path is invalid", ex);
        }
    }

    private void rejectSymbolicLinks(Path root, Path candidate) {
        Path current = root;
        for (Path segment : root.relativize(candidate)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw scopeViolation("scoped path must not contain symbolic links", null);
            }
        }
    }

    private boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private String portable(Path value) {
        return value.toString().replace('\\', '/');
    }

    private WorkspaceOperationException topologyChanged(Throwable cause) {
        return new WorkspaceOperationException(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED,
                "repository root identity changed after scope creation", cause);
    }

    private WorkspaceOperationException scopeViolation(String message, Throwable cause) {
        return new WorkspaceOperationException(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                message, cause);
    }
}
