package com.example.agentweb.infra.workspace;

import com.example.agentweb.domain.workspace.ChangedFileEvidence;
import com.example.agentweb.domain.workspace.RepositoryBaseline;
import com.example.agentweb.domain.workspace.RepositorySelection;
import com.example.agentweb.domain.workspace.SnapshotPurpose;
import com.example.agentweb.domain.workspace.WorkspaceAnomalyEvidence;
import com.example.agentweb.domain.workspace.WorkspaceSnapshot;
import com.example.agentweb.domain.workspace.WorkspaceTopology;
import com.example.agentweb.infra.SqliteInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace Snapshot 写侧 Repository 的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkspaceSnapshotRepositoryTest {

    private static final Instant CAPTURE_STARTED_AT =
            Instant.parse("2026-08-01T10:00:00.123Z");
    private static final Instant FIRST_REPOSITORY_CAPTURED_AT =
            Instant.parse("2026-08-01T10:00:02.456Z");
    private static final Instant SECOND_REPOSITORY_CAPTURED_AT =
            Instant.parse("2026-08-01T10:00:03.789Z");
    private static final Instant CAPTURED_AT =
            Instant.parse("2026-08-01T10:00:05.987Z");
    private static final String HEAD_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HEAD_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_A = repeat('a', 64);
    private static final String HASH_B = repeat('b', 64);
    private static final String HASH_C = repeat('c', 64);
    private static final String HASH_D = repeat('d', 64);

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkspaceSnapshotRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:"
                + tempDir.resolve("workspace-snapshot.db").toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);
        SqliteInitializer initializer = new SqliteInitializer(jdbc);
        initializer.init();
        initializer.init();
        repository = new SqliteWorkspaceSnapshotRepository(jdbc);
    }

    @Test
    void addAndFindByIdShouldRoundTripCompleteSnapshotWithoutLoss() {
        WorkspaceSnapshot source = dirtySnapshot("snapshot-1", "WORKBENCH_RUN_END", HEAD_A);

        repository.add(source);

        WorkspaceSnapshot restored = repository.findById("snapshot-1")
                .orElseThrow(AssertionError::new);
        assertEquals(source.getSnapshotId(), restored.getSnapshotId());
        assertEquals(source.getPurpose(), restored.getPurpose());
        assertEquals(source.getTopology(), restored.getTopology());
        assertEquals(source.getStateHash(), restored.getStateHash());
        assertEquals(source.isClean(), restored.isClean());
        assertEquals(source.getCaptureStartedAt(), restored.getCaptureStartedAt());
        assertEquals(source.getCapturedAt(), restored.getCapturedAt());
        assertEquals(2, restored.getRepositories().size());
        assertBaseline(source.getRepositories().get(0), restored.getRepositories().get(0));
        assertBaseline(source.getRepositories().get(1), restored.getRepositories().get(1));
        assertEquals(2, restored.getAnomalies().size());
        assertEquals(source.getAnomalies(), restored.getAnomalies());
        assertFalse(restored.isClean());
    }

    @Test
    void findByIdShouldReturnEmptyWhenSnapshotDoesNotExist() {
        assertFalse(repository.findById("missing").isPresent());
    }

    @Test
    void addShouldRejectDuplicateSnapshotIdWithoutOverwritingOriginalContent() {
        WorkspaceSnapshot original = dirtySnapshot(
                "snapshot-duplicate", "WORKBENCH_CREATE", HEAD_A);
        WorkspaceSnapshot different = dirtySnapshot(
                "snapshot-duplicate", "WORKBENCH_RUN_START", HEAD_B);
        repository.add(original);

        assertThrows(IllegalStateException.class, () -> repository.add(different));

        WorkspaceSnapshot restored = repository.findById("snapshot-duplicate")
                .orElseThrow(AssertionError::new);
        assertEquals(original.getPurpose(), restored.getPurpose());
        assertEquals(original.getStateHash(), restored.getStateHash());
        assertEquals(HEAD_A, restored.requireRepository("agent-web").getHead());
    }

    @Test
    void findByIdShouldFailFastWhenStoredStateHashDoesNotMatchDomainFactory() {
        repository.add(dirtySnapshot("snapshot-corrupt-state", "WORKBENCH_RUN_END", HEAD_A));
        jdbc.update("UPDATE workspace_snapshot SET state_hash=? WHERE snapshot_id=?",
                HASH_C, "snapshot-corrupt-state");

        assertThrows(IllegalStateException.class,
                () -> repository.findById("snapshot-corrupt-state"));
    }

    @Test
    void findByIdShouldFailFastWhenStoredTopologyHashDoesNotMatchDomainFactory() {
        repository.add(dirtySnapshot("snapshot-corrupt-topology", "WORKBENCH_RUN_END", HEAD_A));
        jdbc.update("UPDATE workspace_snapshot SET topology_hash=? WHERE snapshot_id=?",
                HASH_D, "snapshot-corrupt-topology");

        assertThrows(IllegalStateException.class,
                () -> repository.findById("snapshot-corrupt-topology"));
    }

    @Test
    void findByIdShouldNotReturnAggregateWhenAChangedFileRowIsMissing() {
        repository.add(dirtySnapshot("snapshot-missing-file", "WORKBENCH_RUN_END", HEAD_A));
        jdbc.update("DELETE FROM workspace_snapshot_changed_file WHERE snapshot_id=? "
                        + "AND repository_key=? AND file_order=0",
                "snapshot-missing-file", "agent-web");

        assertThrows(IllegalStateException.class,
                () -> repository.findById("snapshot-missing-file"));
    }

    @Test
    void findByIdShouldNotReturnAggregateWhenAnAnomalyRowIsMissing() {
        repository.add(dirtySnapshot("snapshot-missing-anomaly", "WORKBENCH_RUN_END", HEAD_A));
        jdbc.update("DELETE FROM workspace_snapshot_anomaly WHERE snapshot_id=? "
                        + "AND anomaly_order=0", "snapshot-missing-anomaly");

        assertThrows(IllegalStateException.class,
                () -> repository.findById("snapshot-missing-anomaly"));
    }

    @Test
    void schemaShouldEnforceForeignKeysUniquePositionsAndChecks() {
        WorkspaceSnapshot source = dirtySnapshot("snapshot-constraints", "WORKBENCH_CREATE", HEAD_A);
        repository.add(source);

        assertEquals(1, jdbc.queryForObject("PRAGMA foreign_keys", Integer.class).intValue());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workspace_snapshot_repository (snapshot_id, repository_key, "
                        + "repository_order, repository_root, branch, git_head, clean, diff_hash, "
                        + "captured_at, primary_repository, changed_file_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "missing", "orphan", 0, "/workspace/orphan", "main", HEAD_A, 1,
                HASH_A, CAPTURED_AT.toEpochMilli(), 1, 0));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workspace_snapshot_repository (snapshot_id, repository_key, "
                        + "repository_order, repository_root, branch, git_head, clean, diff_hash, "
                        + "captured_at, primary_repository, changed_file_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                source.getSnapshotId(), "service-c", 0, "/workspace/service-c", "main",
                HEAD_A, 1, HASH_A, CAPTURED_AT.toEpochMilli(), 0, 0));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE workspace_snapshot SET clean=2 WHERE snapshot_id=?",
                source.getSnapshotId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE workspace_snapshot_anomaly SET kind='UNKNOWN_KIND' "
                        + "WHERE snapshot_id=? AND anomaly_order=0", source.getSnapshotId()));
    }

    private WorkspaceSnapshot dirtySnapshot(String snapshotId, String purpose, String firstHead) {
        WorkspaceTopology topology = WorkspaceTopology.of("/workspace",
                RepositorySelection.of("agent-web",
                        Arrays.asList("service-b", "agent-web")));
        ChangedFileEvidence mainFile = ChangedFileEvidence.observed(
                "src/main/java/example/Main.java", " M", HASH_C);
        ChangedFileEvidence secretFile = ChangedFileEvidence.observed(
                "data/secrets.properties", "??", HASH_D);
        RepositoryBaseline primary = RepositoryBaseline.capture(
                "agent-web", "/workspace/agent-web", "feature/workbench", firstHead,
                false, HASH_A, Arrays.asList(secretFile, mainFile),
                FIRST_REPOSITORY_CAPTURED_AT);
        RepositoryBaseline additional = RepositoryBaseline.capture(
                "service-b", "/workspace/service-b", "main", HEAD_B,
                true, HASH_B, Collections.<ChangedFileEvidence>emptyList(),
                SECOND_REPOSITORY_CAPTURED_AT);
        List<WorkspaceAnomalyEvidence> anomalies = Arrays.asList(
                WorkspaceAnomalyEvidence.of(
                        WorkspaceAnomalyEvidence.Kind.OUTPUT_TRUNCATED,
                        "agent-web", "git status output reached configured byte limit"),
                WorkspaceAnomalyEvidence.workspaceLevel(
                        WorkspaceAnomalyEvidence.Kind.SECONDARY_VERIFY_MISMATCH,
                        "repository state changed during the first capture attempt"));
        return WorkspaceSnapshot.capture(snapshotId, SnapshotPurpose.of(purpose), topology,
                Arrays.asList(additional, primary), anomalies,
                CAPTURE_STARTED_AT, CAPTURED_AT);
    }

    private void assertBaseline(RepositoryBaseline expected, RepositoryBaseline actual) {
        assertEquals(expected.getRepositoryKey(), actual.getRepositoryKey());
        assertEquals(expected.getRepositoryRoot(), actual.getRepositoryRoot());
        assertEquals(expected.getBranch(), actual.getBranch());
        assertEquals(expected.getHead(), actual.getHead());
        assertEquals(expected.isClean(), actual.isClean());
        assertEquals(expected.getDiffHash(), actual.getDiffHash());
        assertEquals(expected.getCapturedAt(), actual.getCapturedAt());
        assertEquals(expected.getFiles().size(), actual.getFiles().size());
        for (int index = 0; index < expected.getFiles().size(); index++) {
            ChangedFileEvidence expectedFile = expected.getFiles().get(index);
            ChangedFileEvidence actualFile = actual.getFiles().get(index);
            assertEquals(expectedFile.getPath(), actualFile.getPath());
            assertEquals(expectedFile.getStatus(), actualFile.getStatus());
            assertEquals(expectedFile.getStateFingerprint(), actualFile.getStateFingerprint());
            assertEquals(expectedFile.isSensitive(), actualFile.isSensitive());
        }
    }

    private static String repeat(char ch, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, ch);
        return new String(chars);
    }
}
