package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchCreationReceipt;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_A;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_B;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench 创建请求幂等收据的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchCreationRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkbenchCreationRepository repository;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("creation-receipt.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "receipt-creation-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-receipt");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        repository = new SqliteWorkbenchCreationRepository(jdbc);
    }

    @Test
    void sameOwnerKeyAndRequestHashShouldReplayOneImmutableReceipt() {
        WorkbenchCreationReceipt receipt = WorkbenchCreationReceipt.record(
                OWNER, "create-key", HASH_A, workbench.getId(), NOW.plusSeconds(1));

        repository.add(receipt);
        repository.add(receipt);

        WorkbenchCreationReceipt restored = repository
                .findByOwnerAndIdempotencyKey(OWNER, "create-key")
                .orElseThrow(AssertionError::new);
        assertEquals(workbench.getId(),
                restored.requireReplay(OWNER, "create-key", HASH_A));
        assertEquals(receipt.getOwner(), restored.getOwner());
        assertEquals(receipt.getRequestHash(), restored.getRequestHash());
        assertEquals(receipt.getCreatedAt(), restored.getCreatedAt());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_creation_request", Integer.class));
    }

    @Test
    void sameOwnerAndKeyWithDifferentRequestHashShouldConflictWithoutOverwrite() {
        WorkbenchCreationReceipt original = WorkbenchCreationReceipt.record(
                OWNER, "conflict-key", HASH_A, workbench.getId(), NOW.plusSeconds(1));
        repository.add(original);
        WorkbenchCreationReceipt conflict = WorkbenchCreationReceipt.record(
                OWNER, "conflict-key", HASH_B, workbench.getId(), NOW.plusSeconds(2));

        WorkbenchDomainException exception = assertThrows(
                WorkbenchDomainException.class, () -> repository.add(conflict));

        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, exception.getCode());
        WorkbenchCreationReceipt restored = repository
                .findByOwnerAndIdempotencyKey(OWNER, "conflict-key")
                .orElseThrow(AssertionError::new);
        assertEquals(HASH_A, restored.getRequestHash());
        assertEquals(NOW.plusSeconds(1), restored.getCreatedAt());
    }

    @Test
    void missingReceiptShouldBeEmptyAndForeignKeyShouldRejectOrphan() {
        assertFalse(repository.findByOwnerAndIdempotencyKey(
                OWNER, "missing").isPresent());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO workbench_creation_request "
                        + "(owner_id, owner_name, idempotency_key, request_hash, "
                        + "workbench_id, created_at) VALUES (?,?,?,?,?,?)",
                OWNER.getOwnerId(), OWNER.getOwnerName(), "orphan", HASH_A,
                "missing-workbench", NOW.toEpochMilli()));
    }
}
