package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Workbench 创建时冻结的多仓库授权边界。
 *
 * <p>Workspace Root 只是已选仓库的发现和访问上界，不是可写授权。Scope 只接受
 * Infrastructure 已验证的 {@link ResolvedRepository} 事实，并在一个构造点守护成员集合、
 * 主仓库、真实路径和根身份不变量。创建后不可修改。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
public final class RepositoryScope {

    public static final String HASH_SCHEMA = "repository-scope@1";

    private final String workspaceRoot;
    private final String primaryRepositoryKey;
    private final List<ResolvedRepository> repositories;
    private final String scopeHash;
    private final WorkspaceTopology topology;
    private final Map<String, ResolvedRepository> repositoriesByKey;

    private RepositoryScope(String workspaceRoot, String primaryRepositoryKey,
                            List<ResolvedRepository> repositories, String scopeHash,
                            WorkspaceTopology topology,
                            Map<String, ResolvedRepository> repositoriesByKey) {
        this.workspaceRoot = workspaceRoot;
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositories = Collections.unmodifiableList(repositories);
        this.scopeHash = scopeHash;
        this.topology = topology;
        this.repositoriesByKey = Collections.unmodifiableMap(repositoriesByKey);
    }

    /**
     * 以用户明确选择和 Infrastructure 已验证事实建立不可变授权范围。
     *
     * @param workspaceRealRoot Workspace Root 的真实绝对规范路径
     * @param selection 用户确认的仓库集合和主仓库
     * @param resolvedRepositories 与选择集合一一对应的已验证仓库事实
     * @param maximumRepositories 当前配置允许的仓库数量上限
     * @return 不可变 Repository Scope
     */
    public static RepositoryScope create(
            String workspaceRealRoot, RepositorySelection selection,
            List<ResolvedRepository> resolvedRepositories, int maximumRepositories) {
        if (selection == null) {
            throw new IllegalArgumentException("repository selection must not be null");
        }
        if (maximumRepositories < 1) {
            throw new IllegalArgumentException("maximum repositories must be positive");
        }
        if (resolvedRepositories == null || resolvedRepositories.isEmpty()
                || resolvedRepositories.contains(null)) {
            throw new IllegalArgumentException(
                    "repository scope must contain at least one resolved repository");
        }
        if (resolvedRepositories.size() > maximumRepositories) {
            throw new IllegalArgumentException(
                    "repository scope exceeds configured repository limit");
        }

        Path workspaceRoot = ResolvedRepository.requireNormalizedAbsolutePath(
                workspaceRealRoot, "workspace real root");
        Map<String, ResolvedRepository> repositoriesByKey = indexByKey(
                resolvedRepositories);
        requireExactSelection(selection, repositoriesByKey);
        requireRootsInsideWorkspace(workspaceRoot, repositoriesByKey.values());
        requireUniqueNonNestedRoots(repositoriesByKey.values());

        List<ResolvedRepository> ordered = new ArrayList<ResolvedRepository>(
                repositoriesByKey.values());
        ordered.sort(Comparator.comparing(ResolvedRepository::getRepositoryKey));
        Map<String, ResolvedRepository> orderedByKey = new LinkedHashMap<String, ResolvedRepository>();
        for (ResolvedRepository repository : ordered) {
            orderedByKey.put(repository.getRepositoryKey(), repository);
        }
        String primary = selection.getPrimaryRepositoryKey();
        WorkspaceTopology topology = WorkspaceTopology.of(workspaceRoot.toString(), selection);
        String hash = computeScopeHash(workspaceRoot.toString(), primary, ordered);
        return new RepositoryScope(
                workspaceRoot.toString(), primary, ordered, hash, topology, orderedByKey);
    }

    /**
     * 返回主仓库事实，调用方无需遍历仓库集合重组主仓库规则。
     *
     * @return 唯一主仓库
     */
    public ResolvedRepository primaryRepository() {
        return repositoriesByKey.get(primaryRepositoryKey);
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public String getPrimaryRepositoryKey() {
        return primaryRepositoryKey;
    }

    public List<ResolvedRepository> getRepositories() {
        return repositories;
    }

    /**
     * 返回全部授权仓库的稳定真实根顺序，调用方无需遍历聚合内部集合重新投影。
     */
    public List<String> repositoryRoots() {
        List<String> roots = new ArrayList<String>();
        for (ResolvedRepository repository : repositories) {
            roots.add(repository.getRepositoryRoot());
        }
        return Collections.unmodifiableList(roots);
    }

    /**
     * 将结构化仓库 key 映射为已授权真实根；任一 key 越界时整体拒绝。
     */
    public List<String> requireRepositoryRoots(List<String> repositoryKeys) {
        if (repositoryKeys == null || repositoryKeys.contains(null)) {
            throw new IllegalArgumentException(
                    "repository keys must not be null or contain null");
        }
        List<String> roots = new ArrayList<String>();
        for (String repositoryKey : repositoryKeys) {
            roots.add(requireRepository(repositoryKey).getRepositoryRoot());
        }
        return Collections.unmodifiableList(roots);
    }

    public String getScopeHash() {
        return scopeHash;
    }

    /**
     * 按结构化仓库 key 获取授权仓库。
     *
     * @param repositoryKey 仓库 key
     * @return 已授权仓库
     */
    public ResolvedRepository requireRepository(String repositoryKey) {
        String key = RepositorySelection.normalizeRepositoryKey(repositoryKey);
        ResolvedRepository repository = repositoriesByKey.get(key);
        if (repository == null) {
            throw new IllegalArgumentException("repository is outside the repository scope: " + key);
        }
        return repository;
    }

    /**
     * 判断结构化仓库 key 是否位于授权范围。
     *
     * @param repositoryKey 仓库 key
     * @return key 合法且已授权时为 true
     */
    public boolean containsRepository(String repositoryKey) {
        if (repositoryKey == null || repositoryKey.trim().isEmpty()) {
            return false;
        }
        try {
            return repositoriesByKey.containsKey(
                    RepositorySelection.normalizeRepositoryKey(repositoryKey));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public int repositoryCount() {
        return repositories.size();
    }

    /**
     * 判断快照引用是否与本授权范围创建时固化的 Workspace 拓扑一致。
     *
     * <p>调用方不需要读取并比较 topologyHash，避免在 Application 重组 Scope 规则。</p>
     *
     * @param snapshotReference 待验证的不可变快照引用
     * @return 非空引用且拓扑一致时为 true
     */
    public boolean matchesSnapshotTopology(WorkspaceSnapshotReference snapshotReference) {
        return snapshotReference != null
                && topology.getTopologyHash().equals(snapshotReference.getTopologyHash());
    }

    private static Map<String, ResolvedRepository> indexByKey(
            List<ResolvedRepository> repositories) {
        Map<String, ResolvedRepository> byKey = new LinkedHashMap<String, ResolvedRepository>();
        for (ResolvedRepository repository : repositories) {
            if (byKey.put(repository.getRepositoryKey(), repository) != null) {
                throw new IllegalArgumentException(
                        "duplicate resolved repository key: " + repository.getRepositoryKey());
            }
        }
        return byKey;
    }

    private static void requireExactSelection(
            RepositorySelection selection, Map<String, ResolvedRepository> repositoriesByKey) {
        Set<String> selectedKeys = new LinkedHashSet<String>(selection.getRepositoryKeys());
        if (!selectedKeys.equals(repositoriesByKey.keySet())) {
            throw new IllegalArgumentException(
                    "resolved repositories must match the repository selection exactly");
        }
        if (!repositoriesByKey.containsKey(selection.getPrimaryRepositoryKey())) {
            throw new IllegalArgumentException(
                    "repository scope must contain exactly one selected primary repository");
        }
    }

    private static void requireRootsInsideWorkspace(
            Path workspaceRoot, Iterable<ResolvedRepository> repositories) {
        for (ResolvedRepository repository : repositories) {
            if (!repository.repositoryRootPath().startsWith(workspaceRoot)) {
                throw new IllegalArgumentException(
                        "repository real root must remain inside workspace real root: "
                                + repository.getRepositoryKey());
            }
        }
    }

    private static void requireUniqueNonNestedRoots(
            Iterable<ResolvedRepository> repositories) {
        List<ResolvedRepository> values = new ArrayList<ResolvedRepository>();
        Map<Path, String> keyByRoot = new LinkedHashMap<Path, String>();
        for (ResolvedRepository repository : repositories) {
            Path root = repository.repositoryRootPath();
            String duplicate = keyByRoot.put(root, repository.getRepositoryKey());
            if (duplicate != null) {
                throw new IllegalArgumentException(
                        "resolved repository roots must be unique: " + duplicate + ", "
                                + repository.getRepositoryKey());
            }
            values.add(repository);
        }
        for (int i = 0; i < values.size(); i++) {
            Path left = values.get(i).repositoryRootPath();
            for (int j = i + 1; j < values.size(); j++) {
                Path right = values.get(j).repositoryRootPath();
                if (left.startsWith(right) || right.startsWith(left)) {
                    throw new IllegalArgumentException(
                            "resolved repository roots must not contain each other: "
                                    + values.get(i).getRepositoryKey() + ", "
                                    + values.get(j).getRepositoryKey());
                }
            }
        }
    }

    private static String computeScopeHash(
            String workspaceRoot, String primaryRepositoryKey,
            List<ResolvedRepository> repositories) {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "workspaceRoot", workspaceRoot);
        CanonicalHashing.appendFramed(
                canonical, "primaryRepository", primaryRepositoryKey);
        for (ResolvedRepository repository : repositories) {
            CanonicalHashing.appendFramed(
                    canonical, "repositoryKey", repository.getRepositoryKey());
            CanonicalHashing.appendFramed(
                    canonical, "relativePath", repository.getRelativePath());
            CanonicalHashing.appendFramed(
                    canonical, "repositoryRoot", repository.getRepositoryRoot());
            CanonicalHashing.appendFramed(
                    canonical, "rootFingerprint", repository.getRootFingerprint());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RepositoryScope)) {
            return false;
        }
        RepositoryScope that = (RepositoryScope) other;
        return workspaceRoot.equals(that.workspaceRoot)
                && primaryRepositoryKey.equals(that.primaryRepositoryKey)
                && repositories.equals(that.repositories)
                && scopeHash.equals(that.scopeHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceRoot, primaryRepositoryKey, repositories, scopeHash);
    }
}
