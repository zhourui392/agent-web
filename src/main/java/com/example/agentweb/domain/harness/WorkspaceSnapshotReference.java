package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Objects;

/**
 * 其他聚合安全引用不可变 WorkspaceSnapshot 的最小投影。
 *
 * @author zhourui(V33215020)
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceSnapshotReference {

    private final String snapshotId;
    private final String topologyHash;
    private final String stateHash;
    private final int repositoryCount;

    public WorkspaceSnapshotReference(String snapshotId, String topologyHash, String stateHash,
                                      int repositoryCount) {
        this.snapshotId = DomainText.require(snapshotId, "workspace snapshot id", 128);
        this.topologyHash = DomainText.requireSha256(topologyHash, "workspace topology hash");
        this.stateHash = DomainText.requireSha256(stateHash, "workspace state hash");
        if (repositoryCount < 1) {
            throw new IllegalArgumentException("workspace snapshot must reference at least one repository");
        }
        this.repositoryCount = repositoryCount;
    }

    public static WorkspaceSnapshotReference from(WorkspaceSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("workspace snapshot must not be null");
        }
        return new WorkspaceSnapshotReference(
                snapshot.getSnapshotId(),
                snapshot.getTopology().getTopologyHash(),
                snapshot.getStateHash(),
                snapshot.getTopology().repositoryCount());
    }

    public boolean sameTopology(WorkspaceSnapshotReference other) {
        return other != null && topologyHash.equals(other.topologyHash);
    }

    public boolean sameState(WorkspaceSnapshotReference other) {
        return other != null && stateHash.equals(other.stateHash);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceSnapshotReference)) {
            return false;
        }
        WorkspaceSnapshotReference that = (WorkspaceSnapshotReference) other;
        return repositoryCount == that.repositoryCount
                && snapshotId.equals(that.snapshotId)
                && topologyHash.equals(that.topologyHash)
                && stateHash.equals(that.stateHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotId, topologyHash, stateHash, repositoryCount);
    }
}
