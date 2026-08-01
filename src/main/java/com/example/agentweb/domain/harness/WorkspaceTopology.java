package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 工作区根、主仓库和有序仓库引用集合；只描述成员身份，不描述代码状态。
 *
 * <p>topologyHash 含绝对 workspaceRoot（有意语义，不可跨环境对比）。
 *
 * @author zhourui(V33215020)
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceTopology {

    public static final String HASH_SCHEMA = "workspace-topology@1";

    private final String workspaceRoot;
    private final String primaryRepositoryKey;
    private final List<String> repositoryKeys;
    private final String topologyHash;

    private WorkspaceTopology(String workspaceRoot, String primaryRepositoryKey,
                              List<String> repositoryKeys, String topologyHash) {
        this.workspaceRoot = workspaceRoot;
        this.primaryRepositoryKey = primaryRepositoryKey;
        this.repositoryKeys = Collections.unmodifiableList(repositoryKeys);
        this.topologyHash = topologyHash;
    }

    public static WorkspaceTopology of(String workspaceRoot, RepositorySelection selection) {
        if (selection == null) {
            throw new IllegalArgumentException("repository selection must not be null");
        }
        String root = canonicalizeWorkspaceRoot(workspaceRoot);
        List<String> keys = new ArrayList<String>(selection.getRepositoryKeys());
        String hash = computeTopologyHash(root, selection.getPrimaryRepositoryKey(), keys);
        return new WorkspaceTopology(root, selection.getPrimaryRepositoryKey(), keys, hash);
    }

    public boolean sameTopology(WorkspaceTopology other) {
        return other != null && topologyHash.equals(other.topologyHash);
    }

    public boolean contains(String repositoryKey) {
        return repositoryKeys.contains(RepositorySelection.normalizeRepositoryKey(repositoryKey));
    }

    public int repositoryCount() {
        return repositoryKeys.size();
    }

    static String canonicalizeWorkspaceRoot(String workspaceRoot) {
        String value = DomainText.require(workspaceRoot, "workspace root", 4096);
        String unified = value.replace('\\', '/');
        while (unified.endsWith("/") && unified.length() > 1
                && !(unified.length() == 3 && unified.charAt(1) == ':')) {
            unified = unified.substring(0, unified.length() - 1);
        }
        return unified;
    }

    static String computeTopologyHash(String workspaceRoot, String primaryRepositoryKey,
                                      List<String> repositoryKeys) {
        StringBuilder canonical = new StringBuilder();
        HarnessHashing.appendFramed(canonical, "schema", HASH_SCHEMA);
        HarnessHashing.appendFramed(canonical, "workspaceRoot", workspaceRoot);
        HarnessHashing.appendFramed(canonical, "primaryRepository", primaryRepositoryKey);
        for (String key : repositoryKeys) {
            HarnessHashing.appendFramed(canonical, "repositoryKey", key);
            HarnessHashing.appendFramed(canonical, "relativePath", key);
        }
        return HarnessHashing.sha256(canonical.toString());
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceTopology)) {
            return false;
        }
        WorkspaceTopology that = (WorkspaceTopology) other;
        return workspaceRoot.equals(that.workspaceRoot)
                && primaryRepositoryKey.equals(that.primaryRepositoryKey)
                && repositoryKeys.equals(that.repositoryKeys)
                && topologyHash.equals(that.topologyHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceRoot, primaryRepositoryKey, repositoryKeys, topologyHash);
    }
}
