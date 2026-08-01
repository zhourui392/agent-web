package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次真实 Git 观察事实：拓扑、各仓库基线、工作区 Hash 与采集时间窗口。
 *
 * <p>不可变聚合根；内容变更必须新建 snapshotId。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceSnapshot {

    public static final String STATE_HASH_SCHEMA = "workspace-state@1";

    private final String snapshotId;
    private final SnapshotPurpose purpose;
    private final WorkspaceTopology topology;
    private final List<RepositoryBaseline> repositories;
    private final List<WorkspaceAnomalyEvidence> anomalies;
    private final boolean clean;
    private final String stateHash;
    private final Instant captureStartedAt;
    private final Instant capturedAt;

    private WorkspaceSnapshot(String snapshotId, SnapshotPurpose purpose,
                              WorkspaceTopology topology,
                              List<RepositoryBaseline> repositories,
                              List<WorkspaceAnomalyEvidence> anomalies,
                              boolean clean, String stateHash,
                              Instant captureStartedAt, Instant capturedAt) {
        this.snapshotId = DomainText.require(snapshotId, "workspace snapshot id", 128);
        if (purpose == null) {
            throw new IllegalArgumentException("workspace snapshot purpose must not be null");
        }
        this.purpose = purpose;
        if (topology == null) {
            throw new IllegalArgumentException("workspace topology must not be null");
        }
        this.topology = topology;
        this.repositories = Collections.unmodifiableList(repositories);
        this.anomalies = Collections.unmodifiableList(anomalies);
        this.clean = clean;
        this.stateHash = DomainText.requireSha256(stateHash, "workspace state hash");
        this.captureStartedAt = DomainText.requireTime(captureStartedAt, "capture started at");
        this.capturedAt = DomainText.requireTime(capturedAt, "captured at");
        if (capturedAt.isBefore(captureStartedAt)) {
            throw new IllegalArgumentException("capturedAt must be >= captureStartedAt");
        }
    }

    /**
     * 从拓扑与各仓库基线冻结不可变快照。
     *
     * @param snapshotId       新快照 ID
     * @param purpose          快照采集目的
     * @param topology         工作区拓扑
     * @param repositories     与拓扑一一对应的仓库基线
     * @param anomalies        工作区级异常证据（可空）
     * @param captureStartedAt 采集开始
     * @param capturedAt       采集结束
     * @return 不可变快照
     */
    public static WorkspaceSnapshot capture(String snapshotId, SnapshotPurpose purpose,
                                            WorkspaceTopology topology,
                                            List<RepositoryBaseline> repositories,
                                            List<WorkspaceAnomalyEvidence> anomalies,
                                            Instant captureStartedAt, Instant capturedAt) {
        if (topology == null) {
            throw new IllegalArgumentException("workspace topology must not be null");
        }
        if (repositories == null || repositories.isEmpty() || repositories.contains(null)) {
            throw new IllegalArgumentException(
                    "workspace snapshot must contain non-null repository baselines");
        }
        List<WorkspaceAnomalyEvidence> anomalyList = anomalies == null
                ? Collections.<WorkspaceAnomalyEvidence>emptyList()
                : new ArrayList<WorkspaceAnomalyEvidence>(anomalies);
        if (anomalyList.contains(null)) {
            throw new IllegalArgumentException("workspace anomalies must not contain null");
        }

        Map<String, RepositoryBaseline> byKey = indexBaselines(repositories);
        requireExactTopologyMatch(topology, byKey);

        List<RepositoryBaseline> ordered = new ArrayList<RepositoryBaseline>(byKey.values());
        ordered.sort(Comparator.comparing(RepositoryBaseline::getRepositoryKey));

        boolean allReposClean = true;
        for (RepositoryBaseline baseline : ordered) {
            if (!baseline.isClean()) {
                allReposClean = false;
                break;
            }
        }
        boolean clean = allReposClean && anomalyList.isEmpty();
        String stateHash = computeStateHash(topology.getTopologyHash(), ordered);

        return new WorkspaceSnapshot(snapshotId, purpose, topology, ordered, anomalyList,
                clean, stateHash, captureStartedAt, capturedAt);
    }

    public WorkspaceSnapshotReference reference() {
        return WorkspaceSnapshotReference.from(this);
    }

    public RepositoryBaseline requireRepository(String repositoryKey) {
        String key = RepositorySelection.normalizeRepositoryKey(repositoryKey);
        for (RepositoryBaseline baseline : repositories) {
            if (baseline.getRepositoryKey().equals(key)) {
                return baseline;
            }
        }
        throw new IllegalArgumentException("repository not in snapshot: " + key);
    }

    public boolean sameTopology(WorkspaceSnapshot other) {
        return other != null && topology.sameTopology(other.topology);
    }

    public boolean sameState(WorkspaceSnapshot other) {
        return other != null && stateHash.equals(other.stateHash);
    }

    static String computeStateHash(String topologyHash, List<RepositoryBaseline> ordered) {
        StringBuilder canonical = new StringBuilder();
        CanonicalHashing.appendFramed(canonical, "schema", STATE_HASH_SCHEMA);
        CanonicalHashing.appendFramed(canonical, "topologyHash", topologyHash);
        for (RepositoryBaseline baseline : ordered) {
            CanonicalHashing.appendFramed(
                    canonical, "repositoryKey", baseline.getRepositoryKey());
            CanonicalHashing.appendFramed(canonical, "branch", baseline.getBranch());
            CanonicalHashing.appendFramed(canonical, "head", baseline.getHead());
            CanonicalHashing.appendFramed(canonical, "clean", baseline.isClean());
            CanonicalHashing.appendFramed(canonical, "diffHash", baseline.getDiffHash());
        }
        return CanonicalHashing.sha256(canonical.toString());
    }

    private static Map<String, RepositoryBaseline> indexBaselines(
            List<RepositoryBaseline> repositories) {
        Map<String, RepositoryBaseline> byKey = new LinkedHashMap<String, RepositoryBaseline>();
        for (RepositoryBaseline baseline : repositories) {
            if (byKey.put(baseline.getRepositoryKey(), baseline) != null) {
                throw new IllegalArgumentException(
                        "duplicate repository baseline: " + baseline.getRepositoryKey());
            }
        }
        return byKey;
    }

    private static void requireExactTopologyMatch(WorkspaceTopology topology,
                                                  Map<String, RepositoryBaseline> byKey) {
        if (byKey.size() != topology.repositoryCount()) {
            throw new IllegalArgumentException(
                    "repository baselines must match topology repository count");
        }
        for (String key : topology.getRepositoryKeys()) {
            if (!byKey.containsKey(key)) {
                throw new IllegalArgumentException(
                        "missing repository baseline for topology key: " + key);
            }
        }
        for (String key : byKey.keySet()) {
            if (!topology.getRepositoryKeys().contains(key)) {
                throw new IllegalArgumentException(
                        "repository baseline not in topology: " + key);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceSnapshot)) {
            return false;
        }
        WorkspaceSnapshot that = (WorkspaceSnapshot) other;
        return snapshotId.equals(that.snapshotId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId);
    }
}
