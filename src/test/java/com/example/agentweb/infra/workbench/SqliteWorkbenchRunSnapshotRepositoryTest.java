package com.example.agentweb.infra.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.workbench.DocumentReference;
import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.ReviewOpinion;
import com.example.agentweb.domain.workbench.VerifiedWorkbenchRunAttachment;
import com.example.agentweb.domain.workbench.UploadedAttachmentBinding;
import com.example.agentweb.domain.workbench.VerifiedUploadedConversationAttachment;
import com.example.agentweb.domain.workbench.Workbench;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.domain.workbench.WorkbenchRunSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 不可变 Run Snapshot 及 Review Opinion/Confirmation 的真实 SQLite 测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class SqliteWorkbenchRunSnapshotRepositoryTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private SqliteWorkbenchRunSnapshotRepository snapshotRepository;
    private SqliteReviewOpinionRepository opinionRepository;
    private SqliteReviewModifyConfirmationRepository confirmationRepository;
    private WorkbenchPersistenceFixtures.WorkspaceFixture workspace;
    private Workbench workbench;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = WorkbenchPersistenceFixtures.initializedJdbc(tempDir.resolve("run-snapshot.db"));
        workspace = WorkbenchPersistenceFixtures.persistWorkspace(
                jdbc, tempDir, "run-workspace-snapshot");
        workbench = WorkbenchPersistenceFixtures.newWorkbench(workspace, "workbench-run");
        new SqliteWorkbenchRepository(jdbc).add(workbench);
        snapshotRepository = new SqliteWorkbenchRunSnapshotRepository(jdbc);
        opinionRepository = new SqliteReviewOpinionRepository(jdbc);
        confirmationRepository = new SqliteReviewModifyConfirmationRepository(jdbc);
    }

    @Test
    void reviewFactsShouldRoundTripAndConfirmationMustReferenceExactOpinion() {
        ReviewOpinion opinion = WorkbenchPersistenceFixtures.reviewOpinion(workbench);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(workbench);

        opinionRepository.add(opinion);
        confirmationRepository.add(confirmation);

        ReviewOpinion restoredOpinion = opinionRepository.find(
                        workbench.getId(), opinion.getVersion())
                .orElseThrow(AssertionError::new);
        assertEquals(opinion, restoredOpinion);
        assertEquals(opinion.getReviewedBy(), restoredOpinion.getReviewedBy());
        assertEquals(opinion.getReviewedAt(), restoredOpinion.getReviewedAt());
        ReviewModifyConfirmation restoredConfirmation =
                confirmationRepository.findById(confirmation.getConfirmationId())
                        .orElseThrow(AssertionError::new);
        assertEquals(confirmation, restoredConfirmation);
        assertEquals(confirmation.getConfirmedBy(), restoredConfirmation.getConfirmedBy());
        assertEquals(confirmation.getConfirmedAt(), restoredConfirmation.getConfirmedAt());
        assertThrows(IllegalStateException.class, () -> opinionRepository.add(opinion));
        assertThrows(IllegalStateException.class,
                () -> confirmationRepository.add(confirmation));
    }

    @Test
    void runSnapshotShouldRoundTripScopeCapabilityHandoffPromptRuntimeAndReviewBinding() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot source = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "review-run");

        snapshotRepository.add(source);

        WorkbenchRunSnapshot restored = snapshotRepository.findByRunId("review-run")
                .orElseThrow(AssertionError::new);
        assertSnapshot(source, restored);
        WorkbenchRunSnapshot replayProof = snapshotRepository
                .findByWorkbenchPhaseAndIdempotencyKey(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                        source.getSubmissionIdempotencyKey())
                .orElseThrow(AssertionError::new);
        assertEquals(source.getRunId(), replayProof.getRunId());
        assertEquals(source.getSubmissionRequestHash(),
                replayProof.getSubmissionRequestHash());
        assertEquals(source.getRunId(), snapshotRepository.findReplayCandidate(
                        WorkbenchPersistenceFixtures.OWNER,
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                        source.getSubmissionIdempotencyKey())
                .orElseThrow(AssertionError::new).getRunId());
        assertFalse(snapshotRepository.findReplayCandidate(
                WorkbenchPersistenceFixtures.OWNER_2,
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                source.getSubmissionIdempotencyKey()).isPresent());
        assertFalse(snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                "missing-key").isPresent());
        assertFalse(snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                workbench.getId(), WorkbenchPhase.IMPLEMENT_TEST,
                source.getSubmissionIdempotencyKey()).isPresent());
        assertEquals(2, restored.getCapabilityBinding().getRules().size());
        assertEquals(1, restored.getCapabilityBinding().getSkills().size());
        assertEquals(1, restored.getCapabilityBinding().getMcpServers().size());
        assertEquals(1, restored.getCapabilityBinding().getRejected().size());
        assertEquals(2, restored.getPromptParts().size());
        assertEquals(2,
                restored.getRuntimeEnforcement().getWritableRepositoryKeys().size());
    }

    @Test
    void safeAttachmentFactsShouldRoundTripAndCorruptPathShouldFailClosed() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot base = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "attachment-run");
        DocumentReference reference = DocumentReference.of(
                "agent-web", "docs/design.md");
        VerifiedWorkbenchRunAttachment attachment =
                VerifiedWorkbenchRunAttachment.verify(
                        reference, WorkbenchPersistenceFixtures.HASH_A,
                        reference, WorkbenchPersistenceFixtures.HASH_A,
                        "text/markdown", 128L, false);
        VerifiedUploadedConversationAttachment uploaded =
                VerifiedUploadedConversationAttachment.restore(
                        "attachment-1",
                        new UploadedAttachmentBinding(
                                WorkbenchPersistenceFixtures.OWNER,
                                workbench.getId(), base.getPhase(),
                                "review-conversation", 2),
                        "browser-design.md", "text/markdown", 64L,
                        WorkbenchPersistenceFixtures.HASH_B,
                        WorkbenchPersistenceFixtures.HASH_C,
                        "attachment-1234567890abcdefabcd.md",
                        base.getCreatedAt().plusSeconds(3600), 0L);
        WorkbenchRunSnapshot source = WorkbenchRunSnapshot.create(
                base.getRunId(), base.getWorkbenchId(), base.getPhase(),
                base.getSubmissionIdempotencyKey(),
                base.getSubmissionRequestHash(), base.getRunMode(),
                workbench.getRepositoryScope(),
                base.getWorkspaceSnapshotReference(),
                base.getCapabilityBinding(), base.getOverrideVersion(),
                base.getHandoffSource(), base.getPromptParts(),
                base.getPromptHash(), base.getRuntimeEnforcement(),
                Collections.singletonList(attachment),
                Collections.singletonList(uploaded), confirmation,
                base.getCreatedAt());

        snapshotRepository.add(source);

        WorkbenchRunSnapshot restored = snapshotRepository.findByRunId(
                        source.getRunId())
                .orElseThrow(AssertionError::new);
        assertEquals(Collections.singletonList(attachment),
                restored.getVerifiedAttachments());
        assertEquals(Collections.singletonList(uploaded),
                restored.getVerifiedUploadedAttachments());
        String persisted = jdbc.queryForObject(
                "SELECT attachments_json FROM workbench_run_snapshot WHERE run_id=?",
                String.class, source.getRunId());
        assertFalse(persisted.contains(workspace.scope().getWorkspaceRoot()));
        assertEquals(true, persisted.contains("REPOSITORY_DOCUMENT"));
        assertEquals(true, persisted.contains("UPLOADED_CONVERSATION"));

        jdbc.update("UPDATE workbench_run_snapshot SET attachments_json=? WHERE run_id=?",
                "[{\"repositoryKey\":\"agent-web\","
                        + "\"relativePath\":\"../secret\","
                        + "\"contentVersion\":\""
                        + WorkbenchPersistenceFixtures.HASH_A + "\","
                        + "\"mediaType\":\"text/plain\",\"size\":1}]",
                source.getRunId());
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByRunId(source.getRunId()));
    }

    @Test
    void runSnapshotIsInsertOnlyAndForeignKeysRejectOrphans() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot source = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "immutable-run");
        snapshotRepository.add(source);

        assertThrows(IllegalStateException.class, () -> snapshotRepository.add(source));
        assertFalse(snapshotRepository.findByRunId("missing").isPresent());
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "UPDATE workbench_run_snapshot SET workbench_id=? WHERE run_id=?",
                "missing", source.getRunId()));
    }

    @Test
    void malformedCapabilityJsonAndStoredHashMismatchShouldFailFast() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot malformed = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "malformed-run");
        snapshotRepository.add(malformed);
        jdbc.update("UPDATE workbench_run_snapshot SET capability_bindings_json=? "
                + "WHERE run_id=?", "{}", malformed.getRunId());
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByRunId(malformed.getRunId()));

        WorkbenchRunSnapshot badHash = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "bad-hash-run");
        snapshotRepository.add(badHash);
        jdbc.update("UPDATE workbench_run_snapshot SET capability_snapshot_hash=? "
                        + "WHERE run_id=?",
                WorkbenchPersistenceFixtures.HASH_A, badHash.getRunId());
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByRunId(badHash.getRunId()));
    }

    @Test
    void phaseScopedIdempotencyKeyShouldBindExactlyOneRun() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot first = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "first-run",
                "same-phase-key", WorkbenchPersistenceFixtures.HASH_E);
        WorkbenchRunSnapshot conflicting = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "second-run",
                "same-phase-key", WorkbenchPersistenceFixtures.HASH_F);

        snapshotRepository.add(first);

        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.add(conflicting));
        WorkbenchRunSnapshot restored = snapshotRepository
                .findByWorkbenchPhaseAndIdempotencyKey(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                        "same-phase-key")
                .orElseThrow(AssertionError::new);
        assertEquals("first-run", restored.getRunId());
        assertEquals(WorkbenchPersistenceFixtures.HASH_E,
                restored.getSubmissionRequestHash());
    }

    @Test
    void corruptedSubmissionRequestHashShouldFailClosedOnRestore() {
        ReviewModifyConfirmation confirmation = persistReviewFacts();
        WorkbenchRunSnapshot source = WorkbenchPersistenceFixtures.reviewRunSnapshot(
                workbench, workspace.snapshot(), confirmation, "corrupt-request-hash");
        snapshotRepository.add(source);
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA ignore_check_constraints = ON");
                statement.executeUpdate("UPDATE workbench_run_snapshot "
                        + "SET submission_request_hash='CORRUPTED' "
                        + "WHERE run_id='corrupt-request-hash'");
                statement.execute("PRAGMA ignore_check_constraints = OFF");
            }
            return null;
        });

        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByRunId(source.getRunId()));
        assertThrows(IllegalStateException.class,
                () -> snapshotRepository.findByWorkbenchPhaseAndIdempotencyKey(
                        workbench.getId(), WorkbenchPhase.REVIEW_REFACTOR,
                        source.getSubmissionIdempotencyKey()));
    }

    private ReviewModifyConfirmation persistReviewFacts() {
        ReviewOpinion opinion = WorkbenchPersistenceFixtures.reviewOpinion(workbench);
        ReviewModifyConfirmation confirmation =
                WorkbenchPersistenceFixtures.reviewConfirmation(workbench);
        opinionRepository.add(opinion);
        confirmationRepository.add(confirmation);
        return confirmation;
    }

    private void assertSnapshot(WorkbenchRunSnapshot expected,
                                WorkbenchRunSnapshot actual) {
        assertEquals(expected.getRunId(), actual.getRunId());
        assertEquals(expected.getWorkbenchId(), actual.getWorkbenchId());
        assertEquals(expected.getPhase(), actual.getPhase());
        assertEquals(expected.getSubmissionIdempotencyKey(),
                actual.getSubmissionIdempotencyKey());
        assertEquals(expected.getSubmissionRequestHash(),
                actual.getSubmissionRequestHash());
        assertEquals(expected.getRunMode(), actual.getRunMode());
        assertEquals(expected.getRepositoryScopeHash(), actual.getRepositoryScopeHash());
        assertEquals(expected.getWorkspaceSnapshotReference(),
                actual.getWorkspaceSnapshotReference());
        assertBinding(expected.getCapabilityBinding(), actual.getCapabilityBinding());
        assertEquals(expected.getOverrideVersion(), actual.getOverrideVersion());
        assertEquals(expected.getHandoffSource().getSourcePhase(),
                actual.getHandoffSource().getSourcePhase());
        assertEquals(expected.getHandoffSource().getSourceVersion(),
                actual.getHandoffSource().getSourceVersion());
        assertEquals(expected.getHandoffSource().getSourceHash(),
                actual.getHandoffSource().getSourceHash());
        assertEquals(expected.getPromptParts().size(), actual.getPromptParts().size());
        for (int i = 0; i < expected.getPromptParts().size(); i++) {
            assertEquals(expected.getPromptParts().get(i).getType(),
                    actual.getPromptParts().get(i).getType());
            assertEquals(expected.getPromptParts().get(i).getSource(),
                    actual.getPromptParts().get(i).getSource());
            assertEquals(expected.getPromptParts().get(i).getContentHash(),
                    actual.getPromptParts().get(i).getContentHash());
            assertEquals(expected.getPromptParts().get(i).getContentSize(),
                    actual.getPromptParts().get(i).getContentSize());
        }
        assertEquals(expected.getPromptHash(), actual.getPromptHash());
        assertEquals(expected.getVerifiedAttachments(),
                actual.getVerifiedAttachments());
        assertEquals(expected.getRuntimeEnforcement().getRuntime(),
                actual.getRuntimeEnforcement().getRuntime());
        assertEquals(expected.getRuntimeEnforcement().getRuntimeVersion(),
                actual.getRuntimeEnforcement().getRuntimeVersion());
        assertEquals(expected.getRuntimeEnforcement().getRepositoryScopeHash(),
                actual.getRuntimeEnforcement().getRepositoryScopeHash());
        assertEquals(expected.getRuntimeEnforcement().getPrimaryRepositoryKey(),
                actual.getRuntimeEnforcement().getPrimaryRepositoryKey());
        assertEquals(expected.getRuntimeEnforcement().getRunMode(),
                actual.getRuntimeEnforcement().getRunMode());
        assertEquals(expected.getRuntimeEnforcement().getWritableRepositoryKeys(),
                actual.getRuntimeEnforcement().getWritableRepositoryKeys());
        assertEquals(expected.getRuntimeEnforcement().getTimeoutSeconds(),
                actual.getRuntimeEnforcement().getTimeoutSeconds());
        assertEquals(expected.getRuntimeEnforcement().getOutputLimitBytes(),
                actual.getRuntimeEnforcement().getOutputLimitBytes());
        assertEquals(expected.getReviewConfirmationId(), actual.getReviewConfirmationId());
        assertEquals(expected.getReviewOpinionVersion(), actual.getReviewOpinionVersion());
        assertEquals(expected.getReviewOpinionHash(), actual.getReviewOpinionHash());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
    }

    private void assertBinding(ResolvedCapabilityBinding expected,
                               ResolvedCapabilityBinding actual) {
        assertEquals(expected.getPolicyVersion(), actual.getPolicyVersion());
        assertEquals(expected.getProfileId(), actual.getProfileId());
        assertEquals(expected.getProfileVersion(), actual.getProfileVersion());
        assertEquals(expected.getProfileHash(), actual.getProfileHash());
        assertEquals(expected.getRules(), actual.getRules());
        assertEquals(expected.getSkills(), actual.getSkills());
        assertEquals(expected.getMcpServers(), actual.getMcpServers());
        assertEquals(expected.getRejected(), actual.getRejected());
        assertEquals(expected.getRuntimeCompatibility(), actual.getRuntimeCompatibility());
        assertEquals(expected.getBindingHash(), actual.getBindingHash());
    }
}
