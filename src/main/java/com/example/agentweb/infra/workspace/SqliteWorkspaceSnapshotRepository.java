package com.example.agentweb.infra.workspace;

import com.example.agentweb.domain.workspace.ChangedFileEvidence;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceAnomalyEvidence;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotRepository;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Workspace Snapshot 聚合的 SQLite 持久化实现。
 *
 * @author alex
 * @since 2026-08-01
 */
@Repository
public class SqliteWorkspaceSnapshotRepository implements WorkspaceSnapshotRepository {

    private static final String SNAPSHOT_COLUMNS = "snapshot_id, purpose, workspace_root, "
            + "primary_repository_key, topology_hash, clean, state_hash, capture_started_at, "
            + "captured_at, repository_count, anomaly_count";
    private static final String REPOSITORY_COLUMNS = "snapshot_id, repository_key, "
            + "repository_order, repository_root, branch, git_head, clean, diff_hash, "
            + "captured_at, primary_repository, changed_file_count";
    private static final String FILE_COLUMNS = "snapshot_id, repository_key, file_path, "
            + "file_order, status, state_fingerprint, sensitive";
    private static final String ANOMALY_COLUMNS = "snapshot_id, anomaly_order, kind, "
            + "repository_key, detail";

    private final JdbcTemplate jdbc;

    public SqliteWorkspaceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(WorkspaceSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("workspace snapshot must not be null");
        }
        try {
            insertSnapshot(snapshot);
            insertRepositories(snapshot);
            insertAnomalies(snapshot);
        } catch (DataAccessException ex) {
            throw new IllegalStateException(
                    "workspace snapshot could not be added: " + snapshot.getSnapshotId(), ex);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WorkspaceSnapshot> findById(String snapshotId) {
        List<SnapshotRow> rows = jdbc.query("SELECT " + SNAPSHOT_COLUMNS
                        + " FROM workspace_snapshot WHERE snapshot_id=?",
                this::readSnapshotRow, snapshotId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        SnapshotRow row = rows.get(0);
        try {
            return Optional.of(restore(row));
        } catch (IllegalArgumentException ex) {
            throw corrupt(row.snapshotId, ex.getMessage(), ex);
        }
    }

    private void insertSnapshot(WorkspaceSnapshot snapshot) {
        jdbc.update("INSERT INTO workspace_snapshot (" + SNAPSHOT_COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                snapshot.getSnapshotId(), snapshot.getPurpose().getValue(),
                snapshot.getTopology().getWorkspaceRoot(),
                snapshot.getTopology().getPrimaryRepositoryKey(),
                snapshot.getTopology().getTopologyHash(), snapshot.isClean() ? 1 : 0,
                snapshot.getStateHash(), snapshot.getCaptureStartedAt().toEpochMilli(),
                snapshot.getCapturedAt().toEpochMilli(), snapshot.getRepositories().size(),
                snapshot.getAnomalies().size());
    }

    private void insertRepositories(WorkspaceSnapshot snapshot) {
        for (int repositoryOrder = 0;
             repositoryOrder < snapshot.getRepositories().size(); repositoryOrder++) {
            RepositoryBaseline baseline = snapshot.getRepositories().get(repositoryOrder);
            jdbc.update("INSERT INTO workspace_snapshot_repository (" + REPOSITORY_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    snapshot.getSnapshotId(), baseline.getRepositoryKey(), repositoryOrder,
                    baseline.getRepositoryRoot(), baseline.getBranch(), baseline.getHead(),
                    baseline.isClean() ? 1 : 0, baseline.getDiffHash(),
                    baseline.getCapturedAt().toEpochMilli(),
                    baseline.getRepositoryKey().equals(
                            snapshot.getTopology().getPrimaryRepositoryKey()) ? 1 : 0,
                    baseline.getFiles().size());
            insertChangedFiles(snapshot.getSnapshotId(), baseline);
        }
    }

    private void insertChangedFiles(String snapshotId, RepositoryBaseline baseline) {
        for (int fileOrder = 0; fileOrder < baseline.getFiles().size(); fileOrder++) {
            ChangedFileEvidence file = baseline.getFiles().get(fileOrder);
            jdbc.update("INSERT INTO workspace_snapshot_changed_file (" + FILE_COLUMNS
                            + ") VALUES (?,?,?,?,?,?,?)",
                    snapshotId, baseline.getRepositoryKey(), file.getPath(), fileOrder,
                    file.getStatus(), file.getStateFingerprint(), file.isSensitive() ? 1 : 0);
        }
    }

    private void insertAnomalies(WorkspaceSnapshot snapshot) {
        for (int anomalyOrder = 0; anomalyOrder < snapshot.getAnomalies().size(); anomalyOrder++) {
            WorkspaceAnomalyEvidence anomaly = snapshot.getAnomalies().get(anomalyOrder);
            jdbc.update("INSERT INTO workspace_snapshot_anomaly (" + ANOMALY_COLUMNS
                            + ") VALUES (?,?,?,?,?)",
                    snapshot.getSnapshotId(), anomalyOrder, anomaly.getKind().name(),
                    anomaly.getRepositoryKey(), anomaly.getDetail());
        }
    }

    private WorkspaceSnapshot restore(SnapshotRow row) {
        List<RepositoryRow> repositoryRows = jdbc.query("SELECT " + REPOSITORY_COLUMNS
                        + " FROM workspace_snapshot_repository WHERE snapshot_id=? "
                        + "ORDER BY repository_order",
                this::readRepositoryRow, row.snapshotId);
        List<String> repositoryKeys = new ArrayList<String>();
        List<RepositoryBaseline> baselines = new ArrayList<RepositoryBaseline>();
        int primaryCount = 0;
        String storedPrimaryKey = null;
        for (RepositoryRow repositoryRow : repositoryRows) {
            repositoryKeys.add(repositoryRow.repositoryKey);
            if (repositoryRow.primaryRepository) {
                primaryCount++;
                storedPrimaryKey = repositoryRow.repositoryKey;
            }
            baselines.add(restoreBaseline(repositoryRow));
        }
        if (repositoryRows.size() != row.repositoryCount) {
            throw corrupt(row.snapshotId,
                    "stored repository count does not match repository rows", null);
        }
        if (primaryCount != 1 || !row.primaryRepositoryKey.equals(storedPrimaryKey)) {
            throw corrupt(row.snapshotId,
                    "stored primary repository does not match snapshot topology", null);
        }

        RepositorySelection selection = RepositorySelection.of(
                row.primaryRepositoryKey, repositoryKeys);
        WorkspaceTopology topology = WorkspaceTopology.of(row.workspaceRoot, selection);
        requireHash(row.snapshotId, "topology", row.topologyHash, topology.getTopologyHash());

        List<WorkspaceAnomalyEvidence> anomalies = jdbc.query(
                "SELECT " + ANOMALY_COLUMNS + " FROM workspace_snapshot_anomaly "
                        + "WHERE snapshot_id=? ORDER BY anomaly_order",
                this::readAnomaly, row.snapshotId);
        if (anomalies.size() != row.anomalyCount) {
            throw corrupt(row.snapshotId,
                    "stored anomaly count does not match anomaly rows", null);
        }
        WorkspaceSnapshot snapshot = WorkspaceSnapshot.capture(
                row.snapshotId, SnapshotPurpose.of(row.purpose), topology, baselines, anomalies,
                row.captureStartedAt, row.capturedAt);
        requireHash(row.snapshotId, "state", row.stateHash, snapshot.getStateHash());
        if (row.clean != snapshot.isClean()) {
            throw corrupt(row.snapshotId,
                    "stored clean flag does not match restored snapshot", null);
        }
        return snapshot;
    }

    private RepositoryBaseline restoreBaseline(RepositoryRow row) {
        List<ChangedFileEvidence> files = jdbc.query("SELECT " + FILE_COLUMNS
                        + " FROM workspace_snapshot_changed_file "
                        + "WHERE snapshot_id=? AND repository_key=? ORDER BY file_order",
                this::readChangedFile, row.snapshotId, row.repositoryKey);
        if (files.size() != row.changedFileCount) {
            throw corrupt(row.snapshotId,
                    "stored changed file count does not match changed file rows for "
                            + row.repositoryKey, null);
        }
        return RepositoryBaseline.capture(row.repositoryKey, row.repositoryRoot, row.branch,
                row.head, row.clean, row.diffHash, files, row.capturedAt);
    }

    private SnapshotRow readSnapshotRow(ResultSet rs, int rowNumber) throws SQLException {
        return new SnapshotRow(rs.getString("snapshot_id"), rs.getString("purpose"),
                rs.getString("workspace_root"), rs.getString("primary_repository_key"),
                rs.getString("topology_hash"), rs.getInt("clean") != 0,
                rs.getString("state_hash"), instant(rs, "capture_started_at"),
                instant(rs, "captured_at"), rs.getInt("repository_count"),
                rs.getInt("anomaly_count"));
    }

    private RepositoryRow readRepositoryRow(ResultSet rs, int rowNumber) throws SQLException {
        return new RepositoryRow(rs.getString("snapshot_id"),
                rs.getString("repository_key"), rs.getString("repository_root"),
                rs.getString("branch"), rs.getString("git_head"), rs.getInt("clean") != 0,
                rs.getString("diff_hash"), instant(rs, "captured_at"),
                rs.getInt("primary_repository") != 0, rs.getInt("changed_file_count"));
    }

    private ChangedFileEvidence readChangedFile(ResultSet rs, int rowNumber) throws SQLException {
        return new ChangedFileEvidence(rs.getString("file_path"), rs.getString("status"),
                rs.getString("state_fingerprint"), rs.getInt("sensitive") != 0);
    }

    private WorkspaceAnomalyEvidence readAnomaly(ResultSet rs, int rowNumber)
            throws SQLException {
        return WorkspaceAnomalyEvidence.of(
                WorkspaceAnomalyEvidence.Kind.valueOf(rs.getString("kind")),
                rs.getString("repository_key"), rs.getString("detail"));
    }

    private void requireHash(String snapshotId, String hashName, String stored, String computed) {
        if (!computed.equals(stored)) {
            throw corrupt(snapshotId,
                    "stored " + hashName + " hash does not match restored aggregate", null);
        }
    }

    private IllegalStateException corrupt(String snapshotId, String detail, Throwable cause) {
        return new IllegalStateException(
                "corrupt workspace snapshot " + snapshotId + ": " + detail, cause);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return Instant.ofEpochMilli(rs.getLong(column));
    }

    private static final class SnapshotRow {
        private final String snapshotId;
        private final String purpose;
        private final String workspaceRoot;
        private final String primaryRepositoryKey;
        private final String topologyHash;
        private final boolean clean;
        private final String stateHash;
        private final Instant captureStartedAt;
        private final Instant capturedAt;
        private final int repositoryCount;
        private final int anomalyCount;

        private SnapshotRow(String snapshotId, String purpose, String workspaceRoot,
                            String primaryRepositoryKey, String topologyHash, boolean clean,
                            String stateHash, Instant captureStartedAt, Instant capturedAt,
                            int repositoryCount, int anomalyCount) {
            this.snapshotId = snapshotId;
            this.purpose = purpose;
            this.workspaceRoot = workspaceRoot;
            this.primaryRepositoryKey = primaryRepositoryKey;
            this.topologyHash = topologyHash;
            this.clean = clean;
            this.stateHash = stateHash;
            this.captureStartedAt = captureStartedAt;
            this.capturedAt = capturedAt;
            this.repositoryCount = repositoryCount;
            this.anomalyCount = anomalyCount;
        }
    }

    private static final class RepositoryRow {
        private final String snapshotId;
        private final String repositoryKey;
        private final String repositoryRoot;
        private final String branch;
        private final String head;
        private final boolean clean;
        private final String diffHash;
        private final Instant capturedAt;
        private final boolean primaryRepository;
        private final int changedFileCount;

        private RepositoryRow(String snapshotId, String repositoryKey, String repositoryRoot,
                              String branch, String head, boolean clean, String diffHash,
                              Instant capturedAt, boolean primaryRepository,
                              int changedFileCount) {
            this.snapshotId = snapshotId;
            this.repositoryKey = repositoryKey;
            this.repositoryRoot = repositoryRoot;
            this.branch = branch;
            this.head = head;
            this.clean = clean;
            this.diffHash = diffHash;
            this.capturedAt = capturedAt;
            this.primaryRepository = primaryRepository;
            this.changedFileCount = changedFileCount;
        }
    }
}
