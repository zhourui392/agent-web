package com.example.agentweb.infra.runtime;

import com.example.agentweb.app.runtime.port.WorkspaceLayout;
import lombok.Getter;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把 Provider 文件或工作目录事实收窄为 Repository Key 与相对路径。
 *
 * @author alex
 * @since 2026-08-01
 */
final class RuntimeRepositoryPathResolver {

    private final Path workspaceRoot;
    private final Path primaryRoot;
    private final Map<Path, String> repositoryKeysByRoot;

    RuntimeRepositoryPathResolver(WorkspaceLayout layout) {
        Objects.requireNonNull(layout, "layout");
        this.workspaceRoot = Paths.get(layout.getWorkspaceRoot());
        this.primaryRoot = Paths.get(layout.getPrimaryRepositoryRoot());
        this.repositoryKeysByRoot = new LinkedHashMap<Path, String>();
        for (String rootValue : layout.getReadableRoots()) {
            Path root = Paths.get(rootValue);
            Path relative = workspaceRoot.relativize(root);
            if (relative.getNameCount() == 0 || relative.isAbsolute()
                    || "..".equals(relative.getName(0).toString())) {
                throw new IllegalArgumentException(
                        "repository root cannot be represented by a safe repository key");
            }
            String key = relative.toString().replace('\\', '/');
            if (repositoryKeysByRoot.put(root, key) != null) {
                throw new IllegalArgumentException(
                        "runtime repository roots must be unique");
            }
        }
        if (!repositoryKeysByRoot.containsKey(primaryRoot)) {
            throw new IllegalArgumentException(
                    "runtime primary repository is unavailable");
        }
    }

    String primaryRepositoryKey() {
        return repositoryKeysByRoot.get(primaryRoot);
    }

    String repositoryKeyForWorkingDirectory(String providerWorkingDirectory) {
        if (providerWorkingDirectory == null
                || providerWorkingDirectory.trim().isEmpty()) {
            return primaryRepositoryKey();
        }
        try {
            Path directory = Paths.get(providerWorkingDirectory);
            if (!directory.isAbsolute()) {
                directory = primaryRoot.resolve(directory);
            }
            Path normalized = directory.normalize();
            Map.Entry<Path, String> repository = repositoryContaining(normalized);
            return repository == null
                    ? primaryRepositoryKey() : repository.getValue();
        } catch (InvalidPathException failure) {
            return primaryRepositoryKey();
        }
    }

    ResolvedFile resolveFile(String providerPath) {
        if (providerPath == null || providerPath.trim().isEmpty()
                || containsControlCharacter(providerPath)) {
            throw new IllegalArgumentException("provider file path is invalid");
        }
        try {
            Path candidate = Paths.get(providerPath);
            if (!candidate.isAbsolute()) {
                candidate = primaryRoot.resolve(candidate);
            }
            Path normalized = candidate.normalize();
            Map.Entry<Path, String> repository = repositoryContaining(normalized);
            if (repository == null) {
                throw new IllegalArgumentException(
                        "provider file is outside selected repositories");
            }
            Path relative = repository.getKey().relativize(normalized);
            if (relative.getNameCount() == 0
                    || "..".equals(relative.getName(0).toString())) {
                throw new IllegalArgumentException(
                        "provider file path is not repository-relative");
            }
            return new ResolvedFile(repository.getValue(),
                    relative.toString().replace('\\', '/'));
        } catch (InvalidPathException failure) {
            throw new IllegalArgumentException(
                    "provider file path is invalid", failure);
        }
    }

    private Map.Entry<Path, String> repositoryContaining(Path path) {
        for (Map.Entry<Path, String> entry : repositoryKeysByRoot.entrySet()) {
            if (path.startsWith(entry.getKey())) {
                return entry;
            }
        }
        return null;
    }

    private boolean containsControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 已去除绝对根的安全文件身份。
     */
    @Getter
    static final class ResolvedFile {

        private final String repositoryKey;
        private final String relativePath;

        private ResolvedFile(String repositoryKey, String relativePath) {
            this.repositoryKey = repositoryKey;
            this.relativePath = relativePath;
        }
    }
}
