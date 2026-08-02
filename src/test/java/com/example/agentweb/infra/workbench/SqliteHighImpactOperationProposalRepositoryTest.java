package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.workbench.CommitTarget;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.HighImpactOperation;
import com.example.agentweb.domain.workbench.HighImpactOperationPolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationProposalReceipt;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;

import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_A;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_B;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HASH_C;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.HEAD_A;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.NOW;
import static com.example.agentweb.infra.workbench.WorkbenchPersistenceFixtures.OWNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 高影响操作提案幂等收据的真实 SQLite roundtrip 与唯一冲突测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteHighImpactOperationProposalRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private Workbench workbench;
    private SqliteHighImpactOperationProposalRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(
                tempDir.resolve("operation-proposal.db"));
        WorkbenchPersistenceFixtures.WorkspaceFixture workspace =
                WorkbenchPersistenceFixtures.persistWorkspace(
                        jdbc, tempDir, "operation-proposal-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(
                workspace, "workbench-operation-proposal");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        HighImpactOperation operation = HighImpactOperationPolicy
                .withAuthorizationTtl(Duration.ofMinutes(15))
                .propose(
                        workbench, "operation-1",
                        WorkbenchRunReference.of(
                                "run-1", workbench.getId(),
                                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                                "Run run-1 (REQUIREMENT_ANALYSIS)"),
                        CommitTarget.create(
                                "agent-web", "master", HEAD_A, HASH_A,
                                Collections.singletonList(
                                        DocumentReference.of(
                                                "agent-web", "README.md")),
                                HASH_B, "feat: proposal"),
                        "人工核对预览", OWNER, NOW);
        new SqliteHighImpactOperationRepository(jdbc).add(operation);
        repository = new SqliteHighImpactOperationProposalRepository(jdbc);
    }

    @Test
    void receiptShouldRoundTripWithoutExposingRequestContent() {
        HighImpactOperationProposalReceipt receipt = receipt(HASH_C);

        repository.add(receipt);

        HighImpactOperationProposalReceipt restored = repository.find(
                        OWNER, workbench.getId(), "proposal-key")
                .orElseThrow(AssertionError::new);
        assertEquals(receipt.getOwner(), restored.getOwner());
        assertEquals(receipt.getWorkbenchId(), restored.getWorkbenchId());
        assertEquals(receipt.getIdempotencyKey(), restored.getIdempotencyKey());
        assertEquals(receipt.getRequestHash(), restored.getRequestHash());
        assertEquals(receipt.getOperationId(), restored.getOperationId());
        assertEquals(receipt.getCreatedAt(), restored.getCreatedAt());
    }

    @Test
    void exactDuplicateShouldBeIdempotentAndChangedRequestShouldConflict() {
        HighImpactOperationProposalReceipt receipt = receipt(HASH_C);
        repository.add(receipt);

        repository.add(receipt);

        WorkbenchDomainException conflict = assertThrows(
                WorkbenchDomainException.class,
                () -> repository.add(receipt(HASH_B)));
        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                conflict.getCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM workbench_high_impact_operation_proposal",
                Integer.class));
    }

    @Test
    void operationIdUniqueConstraintShouldRejectAnotherReceiptBinding() {
        repository.add(receipt(HASH_C));
        HighImpactOperationProposalReceipt conflictingOperationBinding =
                HighImpactOperationProposalReceipt.record(
                        OwnerReference.of("owner-2", "Other"),
                        workbench.getId(), "other-key", HASH_C,
                        "operation-1", NOW.plusSeconds(1));

        assertThrows(IllegalStateException.class,
                () -> repository.add(conflictingOperationBinding));
    }

    private HighImpactOperationProposalReceipt receipt(String requestHash) {
        return HighImpactOperationProposalReceipt.record(
                OWNER, workbench.getId(), "proposal-key", requestHash,
                "operation-1", NOW);
    }
}
