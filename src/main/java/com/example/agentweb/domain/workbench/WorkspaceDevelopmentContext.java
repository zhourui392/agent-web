package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.RepositorySelection;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 单次 Repository Scope 探测得到的不可变、安全开发上下文。
 *
 * <p>Scope Hash 只用于绑定授权边界；上下文自身不复制 Scope 中的真实绝对路径。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceDevelopmentContext {

    public static final String HASH_SCHEMA = "workspace-development-context@1";

    private final String repositoryScopeHash;
    private final String primaryRepositoryKey;
    private final List<RepositoryDevelopmentContext> repositories;
    private final String contextHash;
    private final Map<String, RepositoryDevelopmentContext> repositoriesByKey;

    private WorkspaceDevelopmentContext(
            String repositoryScopeHash,
            String primaryRepositoryKey,
            List<RepositoryDevelopmentContext> repositories,
            Map<String, RepositoryDevelopmentContext> repositoriesByKey) {
        this.repositoryScopeHash = repositoryScopeHash;
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositories = Collections.unmodifiableList(repositories);
        this.repositoriesByKey = Collections.unmodifiableMap(repositoriesByKey);
        this.contextHash = computeHash();
    }

    public static WorkspaceDevelopmentContext create(
            String repositoryScopeHash,
            String primaryRepositoryKey,
            List<RepositoryDevelopmentContext> repositories) {
        String scopeHash = DomainText.requireSha256(
                repositoryScopeHash, "repository scope hash");
        if (repositories == null || repositories.isEmpty() || repositories.contains(null)) {
            throw new IllegalArgumentException(
                    "workspace development context must contain repository facts");
        }
        List<String> repositoryKeys = new ArrayList<String>();
        Map<String, RepositoryDevelopmentContext> byKey =
                new LinkedHashMap<String, RepositoryDevelopmentContext>();
        for (RepositoryDevelopmentContext repository : repositories) {
            String key = repository.getRepositoryKey();
            repositoryKeys.add(key);
            if (byKey.put(key, repository) != null) {
                throw new IllegalArgumentException(
                        "workspace development context contains duplicate repository: " + key);
            }
        }
        RepositorySelection selection = RepositorySelection.of(
                primaryRepositoryKey, repositoryKeys);
        List<RepositoryDevelopmentContext> ordered =
                new ArrayList<RepositoryDevelopmentContext>();
        Map<String, RepositoryDevelopmentContext> orderedByKey =
                new LinkedHashMap<String, RepositoryDevelopmentContext>();
        for (String key : selection.getRepositoryKeys()) {
            RepositoryDevelopmentContext repository = byKey.get(key);
            ordered.add(repository);
            orderedByKey.put(key, repository);
        }
        return new WorkspaceDevelopmentContext(scopeHash,
                selection.getPrimaryRepositoryKey(), ordered, orderedByKey);
    }

    public RepositoryDevelopmentContext primaryRepository() {
        return repositoriesByKey.get(primaryRepositoryKey);
    }

    public RepositoryDevelopmentContext requireRepository(String repositoryKey) {
        if (repositoryKey == null || repositoryKey.trim().isEmpty()) {
            throw new IllegalArgumentException("repository key must not be blank");
        }
        String normalized = RepositorySelection.of(
                repositoryKey, Collections.singletonList(repositoryKey))
                .getPrimaryRepositoryKey();
        RepositoryDevelopmentContext repository = repositoriesByKey.get(normalized);
        if (repository == null) {
            throw new IllegalArgumentException(
                    "repository is outside the workspace development context");
        }
        return repository;
    }

    public List<String> repositoryKeys() {
        return Collections.unmodifiableList(
                new ArrayList<String>(repositoriesByKey.keySet()));
    }

    public boolean hasDetectedDevelopmentMetadata() {
        for (RepositoryDevelopmentContext repository : repositories) {
            if (repository.hasDetectedDevelopmentMetadata()) {
                return true;
            }
        }
        return false;
    }

    /** 要求探测事实仍精确绑定本次 Run 冻结的 Repository Scope。 */
    public void requireScope(RepositoryScope repositoryScope) {
        if (repositoryScope == null
                || !repositoryScopeHash.equals(repositoryScope.getScopeHash())
                || !primaryRepositoryKey.equals(
                repositoryScope.getPrimaryRepositoryKey())
                || !repositoryKeys().equals(
                repositoryScope.repositoryKeys())) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    /** Repository Scope 可证明的技术/构建标签；空集合表示沿用平台默认能力。 */
    public Set<String> capabilityTags() {
        Set<String> result = new TreeSet<String>();
        for (RepositoryDevelopmentContext repository : repositories) {
            for (RepositoryTechnologyType technology
                    : repository.getTechnologyTypes()) {
                result.add(capabilityTag(technology));
            }
            for (RepositoryBuildTool buildTool : repository.getBuildTools()) {
                result.add(capabilityTag(buildTool));
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String capabilityTag(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-');
    }

    private String computeHash() {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        CanonicalHashing.appendFramed(
                canonical, "repositoryScopeHash", repositoryScopeHash);
        CanonicalHashing.appendFramed(
                canonical, "primaryRepositoryKey", primaryRepositoryKey);
        for (RepositoryDevelopmentContext repository : repositories) {
            CanonicalHashing.appendFramed(
                    canonical, "repositoryKey", repository.getRepositoryKey());
            CanonicalHashing.appendFramed(
                    canonical, "repositoryContextHash", repository.getContextHash());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceDevelopmentContext)) {
            return false;
        }
        WorkspaceDevelopmentContext that = (WorkspaceDevelopmentContext) other;
        return repositoryScopeHash.equals(that.repositoryScopeHash)
                && primaryRepositoryKey.equals(that.primaryRepositoryKey)
                && repositories.equals(that.repositories)
                && contextHash.equals(that.contextHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(repositoryScopeHash, primaryRepositoryKey,
                repositories, contextHash);
    }
}
