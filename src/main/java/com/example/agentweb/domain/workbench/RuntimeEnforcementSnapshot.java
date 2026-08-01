package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.ResolvedRepository;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime 实际执行边界的不可变摘要；Workspace 父目录不是可写根。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class RuntimeEnforcementSnapshot {

    private final String runtime;
    private final String runtimeVersion;
    private final String repositoryScopeHash;
    private final String primaryRepositoryKey;
    private final RunMode runMode;
    private final List<String> writableRepositoryKeys;
    private final long timeoutSeconds;
    private final long outputLimitBytes;

    private RuntimeEnforcementSnapshot(
            String runtime, String runtimeVersion, String repositoryScopeHash,
            String primaryRepositoryKey, RunMode runMode,
            List<String> writableRepositoryKeys, long timeoutSeconds,
            long outputLimitBytes) {
        this.runtime = DomainText.require(runtime, "runtime name", 128);
        this.runtimeVersion = DomainText.require(
                runtimeVersion, "runtime version", 128);
        this.repositoryScopeHash = DomainText.requireSha256(
                repositoryScopeHash, "runtime repository scope hash");
        this.primaryRepositoryKey = HighImpactTargetSupport.repositoryKey(
                primaryRepositoryKey);
        if (runMode == null) {
            throw new IllegalArgumentException("runtime run mode must not be null");
        }
        this.runMode = runMode;
        this.writableRepositoryKeys = normalizeRepositories(writableRepositoryKeys);
        if (!runMode.modifiesWorkspace() && !this.writableRepositoryKeys.isEmpty()) {
            throw new IllegalArgumentException(
                    "read-only runtime must not contain writable repositories");
        }
        if (timeoutSeconds < 1L || outputLimitBytes < 1L) {
            throw new IllegalArgumentException(
                    "runtime timeout and output limit must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.outputLimitBytes = outputLimitBytes;
    }

    public static RuntimeEnforcementSnapshot readOnly(
            String runtime, String runtimeVersion, String repositoryScopeHash,
            String primaryRepositoryKey, long timeoutSeconds, long outputLimitBytes) {
        return new RuntimeEnforcementSnapshot(
                runtime, runtimeVersion, repositoryScopeHash, primaryRepositoryKey,
                RunMode.DISCUSS_READ_ONLY, Collections.<String>emptyList(),
                timeoutSeconds, outputLimitBytes);
    }

    public static RuntimeEnforcementSnapshot modify(
            String runtime, String runtimeVersion, String repositoryScopeHash,
            String primaryRepositoryKey, List<String> writableRepositoryKeys,
            long timeoutSeconds, long outputLimitBytes) {
        return new RuntimeEnforcementSnapshot(
                runtime, runtimeVersion, repositoryScopeHash, primaryRepositoryKey,
                RunMode.MODIFY_WORKSPACE, writableRepositoryKeys,
                timeoutSeconds, outputLimitBytes);
    }

    /**
     * 按领域 RunMode 固化 Runtime 边界，调用方无需重组读写分支。
     */
    public static RuntimeEnforcementSnapshot forRun(
            String runtime, String runtimeVersion, String repositoryScopeHash,
            String primaryRepositoryKey, RunMode runMode,
            List<String> writableRepositoryKeys,
            long timeoutSeconds, long outputLimitBytes) {
        if (runMode == null) {
            throw new IllegalArgumentException("runtime run mode must not be null");
        }
        if (runMode.modifiesWorkspace()) {
            return modify(
                    runtime, runtimeVersion, repositoryScopeHash,
                    primaryRepositoryKey, writableRepositoryKeys,
                    timeoutSeconds, outputLimitBytes);
        }
        return readOnly(
                runtime, runtimeVersion, repositoryScopeHash,
                primaryRepositoryKey, timeoutSeconds, outputLimitBytes);
    }

    /**
     * 要求执行时加载的不可变 Repository Scope 仍与冻结 Runtime 边界一致。
     */
    public void requireRepositoryScope(RepositoryScope repositoryScope) {
        if (repositoryScope == null
                || !repositoryScopeHash.equals(repositoryScope.getScopeHash())
                || !primaryRepositoryKey.equals(
                repositoryScope.getPrimaryRepositoryKey())) {
            throw new IllegalStateException(
                    "Runtime repository scope does not match persisted snapshot");
        }
        repositoryScope.requireRepositoryRoots(writableRepositoryKeys);
    }

    /**
     * 返回本轮冻结 Scope 的逻辑仓库及实际读写权限，不暴露绝对路径。
     */
    public List<RunRepositoryScopeFact> repositoryScopeFacts(
            RepositoryScope repositoryScope) {
        requireRepositoryScope(repositoryScope);
        Set<String> writable = new HashSet<String>(
                writableRepositoryKeys);
        List<RunRepositoryScopeFact> facts =
                new ArrayList<RunRepositoryScopeFact>();
        for (ResolvedRepository repository
                : repositoryScope.getRepositories()) {
            String repositoryKey = repository.getRepositoryKey();
            RunRepositoryScopeFact.Access access =
                    writable.contains(repositoryKey)
                            ? RunRepositoryScopeFact.Access.WRITE
                            : RunRepositoryScopeFact.Access.READ;
            facts.add(RunRepositoryScopeFact.of(
                    repositoryKey, repository.getRelativePath(),
                    primaryRepositoryKey.equals(repositoryKey), access));
        }
        return Collections.unmodifiableList(facts);
    }

    private static List<String> normalizeRepositories(List<String> repositories) {
        if (repositories == null || repositories.contains(null)) {
            throw new IllegalArgumentException(
                    "runtime writable repositories must not contain null");
        }
        List<String> normalized = new ArrayList<String>();
        Set<String> unique = new HashSet<String>();
        for (String repository : repositories) {
            String key = HighImpactTargetSupport.repositoryKey(repository);
            if (!unique.add(key)) {
                throw new IllegalArgumentException(
                        "runtime writable repositories must not contain duplicates");
            }
            normalized.add(key);
        }
        Collections.sort(normalized);
        return Collections.unmodifiableList(normalized);
    }
}
