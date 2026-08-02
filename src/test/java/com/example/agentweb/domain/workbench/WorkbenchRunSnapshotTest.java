package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.ResolvedCapabilityBinding;
import com.example.agentweb.domain.capability.ResolvedRuleBinding;
import com.example.agentweb.domain.chatrun.ChatRun;
import com.example.agentweb.domain.chatrun.ChatRunId;
import com.example.agentweb.domain.chatrun.ChatRunNotFoundException;
import com.example.agentweb.domain.chatrun.ExecutionContextReference;
import com.example.agentweb.domain.chatrun.RunOrigin;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import com.example.agentweb.domain.workspace.WorkspaceSnapshotReference;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Run Snapshot 的仓库、能力、Handoff、Prompt 与 Runtime 冻结测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");
    private static final WorkbenchId WORKBENCH_ID = WorkbenchId.of("workbench-1");

    @Test
    void createShouldFreezeAllInputsWithoutRunTerminalState() {
        RepositoryScope scope = scope();
        VerifiedWorkbenchRunAttachment attachment = attachment(
                "agent-web", "docs/design.md", repeat('8'), 128L);
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                "run-1", WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                "submit-key-1", repeat('7'),
                RunMode.MODIFY_WORKSPACE, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), Long.valueOf(2L),
                HandoffSnapshotReference.of(
                        WorkbenchPhase.SOLUTION_DESIGN, 4L, repeat('2')),
                Arrays.asList(
                        PromptPartSnapshot.of("HANDOFF", "phase-handoff", repeat('3'), 120),
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        WorkbenchDomainFixtures.repositoryKeys(scope), 1800L, 8388608L),
                Collections.singletonList(attachment),
                null, NOW);

        assertEquals("run-1", snapshot.getRunId());
        assertEquals("submit-key-1", snapshot.getSubmissionIdempotencyKey());
        assertEquals(repeat('7'), snapshot.getSubmissionRequestHash());
        assertEquals(scope.getScopeHash(), snapshot.getRepositoryScopeHash());
        assertEquals(binding().getBindingHash(),
                snapshot.getCapabilityBinding().getBindingHash());
        assertEquals(2L, snapshot.getOverrideVersion().longValue());
        assertEquals(4L, snapshot.getHandoffSource().getSourceVersion());
        assertEquals(2, snapshot.getPromptParts().size());
        assertEquals(repeat('5'), snapshot.getPromptHash());
        assertEquals(Collections.singletonList(attachment),
                snapshot.getVerifiedAttachments());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getPromptParts().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.getVerifiedAttachments().clear());

        HandoffReception reception = HandoffReception.accept(
                WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                WorkbenchPhase.SOLUTION_DESIGN, 4L, repeat('2'),
                owner(), NOW);
        snapshot.requireHandoffReception(reception);
        assertRunBindingCorrupted(() -> snapshot.requireHandoffReception(
                HandoffReception.accept(
                        WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                        WorkbenchPhase.SOLUTION_DESIGN, 3L, repeat('2'),
                        owner(), NOW)));
    }

    @Test
    void snapshotShouldRejectAttachmentOutsideFrozenRepositoryScope() {
        RepositoryScope scope = scope();
        VerifiedWorkbenchRunAttachment outside = attachment(
                "unselected", "secret.txt", repeat('8'), 6L);

        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunSnapshot.create(
                        "run-outside-attachment", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "submit-outside-attachment", repeat('7'),
                        RunMode.DISCUSS_READ_ONLY, scope,
                        WorkbenchDomainFixtures.snapshotReference(
                                "snapshot-1", repeat('1')),
                        binding(), null, null,
                        Collections.singletonList(PromptPartSnapshot.of(
                                "USER_INPUT", "user", repeat('4'), 32)),
                        repeat('5'),
                        RuntimeEnforcementSnapshot.readOnly(
                                "CODEX", "0.42", scope.getScopeHash(),
                                "agent-web", 1800L, 8388608L),
                        Collections.singletonList(outside), null, NOW));
    }

    @Test
    void readOnlySnapshotShouldHaveNoWritableRepositories() {
        RepositoryScope scope = scope();
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                "run-read", WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-read", repeat('7'),
                RunMode.DISCUSS_READ_ONLY, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), null, null,
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        1800L, 8388608L),
                null, NOW);

        assertTrue(snapshot.getRuntimeEnforcement().getWritableRepositoryKeys().isEmpty());
    }

    @Test
    void snapshotShouldRejectScopeCapabilityAndRuntimeMismatch() {
        RepositoryScope scope = scope();
        WorkspaceSnapshotReference wrongSnapshot = new WorkspaceSnapshotReference(
                "snapshot-wrong", repeat('9'), repeat('1'), 2);

        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunSnapshot.create(
                        "run-1", WORKBENCH_ID, WorkbenchPhase.IMPLEMENT_TEST,
                        "submit-key-mismatch", repeat('7'),
                        RunMode.MODIFY_WORKSPACE, scope, wrongSnapshot,
                        binding(), null, null,
                        Collections.singletonList(
                                PromptPartSnapshot.of(
                                        "USER_INPUT", "user", repeat('4'), 32)),
                        repeat('5'),
                        RuntimeEnforcementSnapshot.modify(
                                "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                                Collections.singletonList("agent-web"),
                                1800L, 8388608L),
                        null, NOW));
    }

    @Test
    void reviewModifySnapshotShouldRequireVersionedConfirmationForSameWorkbench() {
        RepositoryScope scope = scope();
        OwnerReference owner = OwnerReference.of("user-1", "Alex");
        ReviewModifyConfirmation confirmation = ReviewModifyConfirmation.confirm(
                "confirmation-1",
                ReviewOpinion.record(WORKBENCH_ID, 2L, repeat('6'), owner,
                        NOW.minusSeconds(2)),
                owner, NOW.minusSeconds(1));

        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                "run-review", WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                "submit-key-review", repeat('7'),
                RunMode.MODIFY_WORKSPACE, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), null,
                HandoffSnapshotReference.of(
                        WorkbenchPhase.IMPLEMENT_TEST, 1L, repeat('2')),
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        WorkbenchDomainFixtures.repositoryKeys(scope), 1800L, 8388608L),
                confirmation, NOW);

        assertEquals(2L, snapshot.getReviewOpinionVersion().longValue());
        snapshot.requireReviewConfirmation(confirmation);
        ReviewModifyConfirmation wrongConfirmation =
                ReviewModifyConfirmation.confirm(
                        "confirmation-2",
                        ReviewOpinion.record(
                                WORKBENCH_ID, 3L, repeat('6'), owner,
                                NOW.minusSeconds(2)),
                        owner, NOW.minusSeconds(1));
        assertRunBindingCorrupted(
                () -> snapshot.requireReviewConfirmation(wrongConfirmation));
        WorkbenchRunSnapshot snapshotWithoutConfirmation = WorkbenchRunSnapshot.create(
                "run-review-2", WORKBENCH_ID, WorkbenchPhase.REVIEW_REFACTOR,
                "submit-key-review-2", repeat('7'),
                RunMode.MODIFY_WORKSPACE, scope,
                snapshot.getWorkspaceSnapshotReference(), binding(), null,
                snapshot.getHandoffSource(), snapshot.getPromptParts(), repeat('5'),
                snapshot.getRuntimeEnforcement(), null, NOW);
        assertNull(snapshotWithoutConfirmation.getReviewConfirmationId());
    }

    @Test
    void restoreShouldRehydrateFrozenReviewProofWithoutFabricatingConfirmation() {
        RepositoryScope scope = scope();
        WorkbenchRunSnapshot restored = WorkbenchRunSnapshot.restore(
                "run-review-restored", WORKBENCH_ID,
                WorkbenchPhase.REVIEW_REFACTOR,
                "submit-key-restored", repeat('7'),
                RunMode.MODIFY_WORKSPACE, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), Long.valueOf(3L),
                HandoffSnapshotReference.of(
                        WorkbenchPhase.IMPLEMENT_TEST, 2L, repeat('2')),
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        WorkbenchDomainFixtures.repositoryKeys(scope), 1800L, 8388608L),
                "confirmation-restored", Long.valueOf(7L), repeat('6'), NOW);

        assertEquals("confirmation-restored", restored.getReviewConfirmationId());
        assertEquals(7L, restored.getReviewOpinionVersion().longValue());
        assertEquals(repeat('6'), restored.getReviewOpinionHash());
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunSnapshot.restore(
                        "run-review-corrupt", WORKBENCH_ID,
                        WorkbenchPhase.REVIEW_REFACTOR,
                        "submit-key-corrupt", repeat('7'),
                        RunMode.MODIFY_WORKSPACE, scope,
                        restored.getWorkspaceSnapshotReference(), binding(), null,
                        restored.getHandoffSource(), restored.getPromptParts(), repeat('5'),
                        restored.getRuntimeEnforcement(),
                        "confirmation-restored", null, repeat('6'), NOW));
    }

    @Test
    void requireReplayShouldReturnBoundRunOnlyForSameSubmissionFacts() {
        RepositoryScope scope = scope();
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                "run-replayed", WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-replayed", repeat('7'),
                RunMode.DISCUSS_READ_ONLY, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), null, null,
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        1800L, 8388608L),
                null, NOW);

        assertEquals("run-replayed", snapshot.requireReplay(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-replayed", repeat('7')));

        assertIdempotencyConflict(() -> snapshot.requireReplay(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-replayed", repeat('8')));
        assertIdempotencyConflict(() -> snapshot.requireReplay(
                WORKBENCH_ID, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "another-key", repeat('7')));
        assertIdempotencyConflict(() -> snapshot.requireReplay(
                WorkbenchId.of("workbench-2"),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-replayed", repeat('7')));
        assertIdempotencyConflict(() -> snapshot.requireReplay(
                WORKBENCH_ID, WorkbenchPhase.SOLUTION_DESIGN,
                "submit-key-replayed", repeat('7')));
    }

    @Test
    void ownerScopedReplayShouldRequireExactOwnerAndWorkbenchBinding() {
        Workbench ownedWorkbench = workbench(WORKBENCH_ID);
        WorkbenchRunSnapshot snapshot = readOnlySnapshot(
                WORKBENCH_ID, "run-owner-replayed");

        assertEquals("run-owner-replayed", snapshot.requireReplay(
                ownedWorkbench, owner(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-run-owner-replayed", repeat('7')));

        WorkbenchDomainException foreignOwner = assertThrows(
                WorkbenchDomainException.class,
                () -> snapshot.requireReplay(
                        ownedWorkbench,
                        OwnerReference.of("user-2", "Taylor"),
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "submit-run-owner-replayed", repeat('7')));
        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED,
                foreignOwner.getCode());
        assertIdempotencyConflict(() -> snapshot.requireReplay(
                workbench(WorkbenchId.of("workbench-2")), owner(),
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-run-owner-replayed", repeat('7')));
    }

    @Test
    void requireExactRunShouldAcceptOnlySnapshotWorkbenchAndExecutionContext() {
        Workbench workbench = workbench(WORKBENCH_ID);
        WorkbenchRunSnapshot snapshot = readOnlySnapshot(
                WORKBENCH_ID, "run-authorized");
        ChatRun run = workbenchRun(
                "run-authorized", "workbench-1:REQUIREMENT_ANALYSIS");

        snapshot.requireExactRun(workbench, run, "run-authorized");

        assertThrows(ChatRunNotFoundException.class,
                () -> snapshot.requireExactRun(
                        workbench, run, "another-run"));
        assertThrows(ChatRunNotFoundException.class,
                () -> snapshot.requireExactRun(
                        workbench(WorkbenchId.of("workbench-2")),
                        run, "run-authorized"));
        assertThrows(ChatRunNotFoundException.class,
                () -> snapshot.requireExactRun(
                        workbench,
                        workbenchRun(
                                "run-authorized", "workbench-2:REQUIREMENT_ANALYSIS"),
                        "run-authorized"));
        assertThrows(ChatRunNotFoundException.class,
                () -> snapshot.requireExactRun(
                        workbench,
                        ChatRun.submit(
                                ChatRunId.of("run-authorized"), "session-1", 1L,
                                "submit-run-authorized", false, NOW),
                        "run-authorized"));
    }

    @Test
    void snapshotShouldRejectMissingOrPlaceholderSubmissionProof() {
        RepositoryScope scope = scope();

        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunSnapshot.create(
                        "run-null-proof", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        null, repeat('7'), RunMode.DISCUSS_READ_ONLY, scope,
                        WorkbenchDomainFixtures.snapshotReference(
                                "snapshot-1", repeat('1')),
                        binding(), null, null,
                        Collections.singletonList(PromptPartSnapshot.of(
                                "USER_INPUT", "user", repeat('4'), 32)),
                        repeat('5'),
                        RuntimeEnforcementSnapshot.readOnly(
                                "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                                1800L, 8388608L),
                        null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchRunSnapshot.create(
                        "run-placeholder-proof", WORKBENCH_ID,
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        "submit-key", "UNKNOWN", RunMode.DISCUSS_READ_ONLY, scope,
                        WorkbenchDomainFixtures.snapshotReference(
                                "snapshot-1", repeat('1')),
                        binding(), null, null,
                        Collections.singletonList(PromptPartSnapshot.of(
                                "USER_INPUT", "user", repeat('4'), 32)),
                        repeat('5'),
                        RuntimeEnforcementSnapshot.readOnly(
                                "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                                1800L, 8388608L),
                        null, NOW));
    }

    @Test
    void snapshotShouldRequireExactPrivatePromptPayloadForSameRun() {
        RepositoryScope scope = scope();
        WorkbenchRunPromptPayload payload =
                WorkbenchRunPromptPayload.freeze(
                        "run-prompt", "exact private prompt",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, NOW);
        WorkbenchRunSnapshot snapshot = WorkbenchRunSnapshot.create(
                "run-prompt", WORKBENCH_ID,
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-key-prompt", repeat('7'),
                RunMode.DISCUSS_READ_ONLY, scope,
                WorkbenchDomainFixtures.snapshotReference(
                        "snapshot-1", repeat('1')),
                binding(), null, null,
                Collections.singletonList(PromptPartSnapshot.of(
                        "USER_INPUT", "user", repeat('4'), 32)),
                payload.getPromptHash(),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        1800L, 8388608L),
                null, NOW);

        snapshot.requirePromptPayload(payload);

        WorkbenchRunPromptPayload wrongRun =
                WorkbenchRunPromptPayload.freeze(
                        "another-run", "exact private prompt",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, NOW);
        WorkbenchRunPromptPayload wrongPrompt =
                WorkbenchRunPromptPayload.freeze(
                        "run-prompt", "different private prompt",
                        WorkbenchPromptHistoryDelivery.PROMPT_PREFIX, NOW);
        assertRunBindingCorrupted(
                () -> snapshot.requirePromptPayload(wrongRun));
        assertRunBindingCorrupted(
                () -> snapshot.requirePromptPayload(wrongPrompt));
        snapshot.requireHandoffReception(null);
        snapshot.requireReviewConfirmation(null);
    }

    @Test
    void finishRequiredRunShouldStrictlyReleaseMatchingPhaseAndModifyLease() {
        Workbench workbench = preparedModifyWorkbench(WORKBENCH_ID, "run-terminal");
        WorkbenchRunSnapshot snapshot = modifySnapshot(
                WORKBENCH_ID, "run-terminal");
        long version = workbench.getVersion();

        snapshot.finishRequiredRun(
                workbench, "run-terminal", NOW.plusSeconds(3));

        assertNull(workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertNull(workbench.getActiveWriteRunReference());
        assertEquals(version + 1L, workbench.getVersion());
        assertEquals(NOW.plusSeconds(3), workbench.getUpdatedAt());
    }

    @Test
    void finishRequiredRunShouldReleaseReadOnlyPhaseWithoutWriteLease() {
        Workbench workbench = workbench(WORKBENCH_ID);
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-analysis",
                owner(), NOW.plusSeconds(1));
        workbench.prepareRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "read-run",
                RunMode.DISCUSS_READ_ONLY, owner(), NOW.plusSeconds(2));
        WorkbenchRunSnapshot snapshot = readOnlySnapshot(
                WORKBENCH_ID, "read-run");
        long version = workbench.getVersion();
        assertNull(workbench.getActiveWriteRunReference());

        snapshot.finishRequiredRun(
                workbench, "read-run", NOW.plusSeconds(3));

        assertNull(workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .getActiveRunReference());
        assertNull(workbench.getActiveWriteRunReference());
        assertEquals(version + 1L, workbench.getVersion());
    }

    @Test
    void finishRequiredRunShouldRejectWrongCandidateWithoutChangingWorkbench() {
        Workbench workbench = preparedModifyWorkbench(WORKBENCH_ID, "run-terminal");
        WorkbenchRunSnapshot snapshot = modifySnapshot(
                WORKBENCH_ID, "run-terminal");
        ActiveRunReference phaseRun = workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference();
        ActiveRunReference writeLease = workbench.getActiveWriteRunReference();
        long version = workbench.getVersion();
        Instant updatedAt = workbench.getUpdatedAt();

        assertRunBindingCorrupted(() -> snapshot.finishRequiredRun(
                workbench, "different-run", NOW.plusSeconds(3)));

        assertSame(phaseRun, workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertSame(writeLease, workbench.getActiveWriteRunReference());
        assertEquals(version, workbench.getVersion());
        assertEquals(updatedAt, workbench.getUpdatedAt());
    }

    @Test
    void finishRequiredRunShouldRejectForeignWorkbenchWithoutChangingIt() {
        Workbench foreign = preparedModifyWorkbench(
                WorkbenchId.of("workbench-2"), "run-terminal");
        WorkbenchRunSnapshot snapshot = modifySnapshot(
                WORKBENCH_ID, "run-terminal");
        ActiveRunReference phaseRun = foreign.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference();
        ActiveRunReference writeLease = foreign.getActiveWriteRunReference();
        long version = foreign.getVersion();

        assertRunBindingCorrupted(() -> snapshot.finishRequiredRun(
                foreign, "run-terminal", NOW.plusSeconds(3)));

        assertSame(phaseRun, foreign.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertSame(writeLease, foreign.getActiveWriteRunReference());
        assertEquals(version, foreign.getVersion());
    }

    @Test
    void finishRequiredRunShouldRejectMissingPhaseReferenceWithoutMutation() {
        Workbench workbench = workbench(WORKBENCH_ID);
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "conversation-implement",
                owner(), NOW.plusSeconds(1));
        WorkbenchRunSnapshot snapshot = modifySnapshot(
                WORKBENCH_ID, "run-terminal");
        long version = workbench.getVersion();
        Instant updatedAt = workbench.getUpdatedAt();

        assertRunBindingCorrupted(() -> snapshot.finishRequiredRun(
                workbench, "run-terminal", NOW.plusSeconds(3)));

        assertNull(workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertNull(workbench.getActiveWriteRunReference());
        assertEquals(version, workbench.getVersion());
        assertEquals(updatedAt, workbench.getUpdatedAt());
    }

    @Test
    void finishRequiredRunShouldNotClearNewRunThatReplacedSnapshotRun() {
        Workbench workbench = preparedModifyWorkbench(WORKBENCH_ID, "run-terminal");
        WorkbenchRunSnapshot snapshot = modifySnapshot(
                WORKBENCH_ID, "run-terminal");
        assertTrue(workbench.finishRun(
                WorkbenchPhase.IMPLEMENT_TEST, "run-terminal", NOW.plusSeconds(3)));
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, "new-run",
                RunMode.MODIFY_WORKSPACE, owner(), NOW.plusSeconds(4));
        ActiveRunReference newPhaseRun = workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference();
        ActiveRunReference newWriteLease = workbench.getActiveWriteRunReference();
        long version = workbench.getVersion();

        assertRunBindingCorrupted(() -> snapshot.finishRequiredRun(
                workbench, "run-terminal", NOW.plusSeconds(5)));

        assertSame(newPhaseRun, workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertSame(newWriteLease, workbench.getActiveWriteRunReference());
        assertEquals("new-run", newPhaseRun.getRunId());
        assertEquals(version, workbench.getVersion());
    }

    private static void assertIdempotencyConflict(Runnable replay) {
        WorkbenchDomainException error = assertThrows(
                WorkbenchDomainException.class, replay::run);
        assertEquals(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, error.getCode());
    }

    private static void assertRunBindingCorrupted(Runnable finishing) {
        WorkbenchDomainException error = assertThrows(
                WorkbenchDomainException.class, finishing::run);
        assertEquals(WorkbenchErrorCode.RUN_BINDING_CORRUPTED, error.getCode());
    }

    private static Workbench preparedModifyWorkbench(
            WorkbenchId workbenchId, String runId) {
        Workbench workbench = workbench(workbenchId);
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "conversation-implement",
                owner(), NOW.plusSeconds(1));
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, runId,
                RunMode.MODIFY_WORKSPACE, owner(), NOW.plusSeconds(2));
        return workbench;
    }

    private static Workbench workbench(WorkbenchId workbenchId) {
        RepositoryScope scope = scope();
        return Workbench.create(
                workbenchId, owner(), "Workbench", "Implement terminal handling",
                AgentType.CODEX, "local", scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                NOW);
    }

    private static WorkbenchRunSnapshot modifySnapshot(
            WorkbenchId workbenchId, String runId) {
        RepositoryScope scope = scope();
        return WorkbenchRunSnapshot.create(
                runId, workbenchId, WorkbenchPhase.IMPLEMENT_TEST,
                "submit-" + runId, repeat('7'), RunMode.MODIFY_WORKSPACE, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), null,
                HandoffSnapshotReference.of(
                        WorkbenchPhase.SOLUTION_DESIGN, 1L, repeat('2')),
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.modify(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        WorkbenchDomainFixtures.repositoryKeys(scope), 1800L, 8388608L),
                null, NOW.plusSeconds(2));
    }

    private static WorkbenchRunSnapshot readOnlySnapshot(
            WorkbenchId workbenchId, String runId) {
        RepositoryScope scope = scope();
        return WorkbenchRunSnapshot.create(
                runId, workbenchId, WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "submit-" + runId, repeat('7'), RunMode.DISCUSS_READ_ONLY, scope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('1')),
                binding(), null, null,
                Collections.singletonList(
                        PromptPartSnapshot.of("USER_INPUT", "user", repeat('4'), 32)),
                repeat('5'),
                RuntimeEnforcementSnapshot.readOnly(
                        "CODEX", "0.42", scope.getScopeHash(), "agent-web",
                        1800L, 8388608L),
                null, NOW.plusSeconds(2));
    }

    private static OwnerReference owner() {
        return OwnerReference.of("user-1", "Alex");
    }

    private static ChatRun workbenchRun(
            String runId, String originReference) {
        return ChatRun.submit(
                ChatRunId.of(runId), "session-1", 1L,
                "submit-" + runId, false, RunOrigin.WORKBENCH,
                ExecutionContextReference.of(originReference, runId), NOW);
    }

    private static RepositoryScope scope() {
        return WorkbenchDomainFixtures.repositoryScope();
    }

    private static ResolvedCapabilityBinding binding() {
        return ResolvedCapabilityBinding.resolve(
                "policy-1", "implement-test", "1", repeat('a'),
                Collections.singletonList(new ResolvedRuleBinding(
                        "platform/safety", "1", "platform", repeat('b'), true,
                        "强制安全规则")),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                "codex-compatible");
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static VerifiedWorkbenchRunAttachment attachment(
            String repositoryKey, String relativePath,
            String contentHash, long size) {
        DocumentReference reference = DocumentReference.of(
                repositoryKey, relativePath);
        return VerifiedWorkbenchRunAttachment.verify(
                reference, contentHash, reference, contentHash,
                "text/plain", size, false);
    }
}
