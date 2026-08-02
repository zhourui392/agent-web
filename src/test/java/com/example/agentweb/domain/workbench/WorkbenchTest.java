package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 聚合的阶段、会话、Owner 与写运行租约测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final OwnerReference OWNER = OwnerReference.of("user-1", "Alex");

    @Test
    void createShouldBuildExactlyFourIndependentPhases() {
        Workbench workbench = newWorkbench();

        assertEquals(WorkbenchStatus.ACTIVE, workbench.getStatus());
        assertEquals(4, workbench.getPhases().size());
        assertEquals(Arrays.asList(WorkbenchPhase.values()),
                workbench.getPhases().stream()
                        .map(WorkbenchPhaseState::getPhase)
                        .collect(java.util.stream.Collectors.toList()));
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            WorkbenchPhaseState state = workbench.phase(phase);
            assertEquals(WorkbenchPhaseStatus.NOT_STARTED, state.getStatus());
            assertEquals(0, state.getConversationGeneration());
            assertTrue(state.getConversationHistory().isEmpty());
            assertNull(state.getActiveRunReference());
        }
        assertEquals(0L, workbench.getVersion());
    }

    @Test
    void bindConversationShouldBeStableAndIdempotent() {
        Workbench workbench = newWorkbench();

        assertTrue(workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                NOW.plusSeconds(1)));
        assertFalse(workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                NOW.plusSeconds(2)));

        WorkbenchPhaseState phase = workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS);
        assertEquals("conversation-1", phase.currentConversation().getConversationId());
        assertEquals(0, phase.currentConversation().getGeneration());
        assertEquals(1, phase.getConversationHistory().size());
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.bindConversation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-2", OWNER,
                        NOW.plusSeconds(3)));
    }

    @Test
    void restartConversationShouldIncrementGenerationAndRetainReadOnlyHistory() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(WorkbenchPhase.SOLUTION_DESIGN, "conversation-1", OWNER,
                NOW.plusSeconds(1));
        workbench.prepareRun(WorkbenchPhase.SOLUTION_DESIGN, "run-1",
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(2));
        workbench.finishRun(WorkbenchPhase.SOLUTION_DESIGN, "run-1", NOW.plusSeconds(3));

        assertTrue(workbench.restartConversation(
                WorkbenchPhase.SOLUTION_DESIGN, "conversation-2", OWNER,
                NOW.plusSeconds(4)));

        WorkbenchPhaseState phase = workbench.phase(WorkbenchPhase.SOLUTION_DESIGN);
        assertEquals(1, phase.getConversationGeneration());
        assertEquals("conversation-2", phase.currentConversation().getConversationId());
        assertEquals(2, phase.getConversationHistory().size());
        assertEquals(NOW.plusSeconds(4), phase.getConversationHistory().get(0).getRetiredAt());
        assertNull(phase.currentConversation().getRetiredAt());
    }

    @Test
    void restartConversationShouldRejectNotStartedCompletedAndActivePhase() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(WorkbenchPhase.REVIEW_REFACTOR, "conversation-1", OWNER,
                NOW.plusSeconds(1));

        assertThrows(WorkbenchDomainException.class,
                () -> workbench.restartConversation(
                        WorkbenchPhase.REVIEW_REFACTOR, "conversation-2", OWNER,
                        NOW.plusSeconds(2)));

        workbench.prepareRun(WorkbenchPhase.REVIEW_REFACTOR, "run-1",
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(3));
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.restartConversation(
                        WorkbenchPhase.REVIEW_REFACTOR, "conversation-2", OWNER,
                        NOW.plusSeconds(4)));
        workbench.finishRun(WorkbenchPhase.REVIEW_REFACTOR, "run-1", NOW.plusSeconds(5));
        workbench.completePhase(WorkbenchPhase.REVIEW_REFACTOR, OWNER, NOW.plusSeconds(6));
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.restartConversation(
                        WorkbenchPhase.REVIEW_REFACTOR, "conversation-2", OWNER,
                        NOW.plusSeconds(7)));
    }

    @Test
    void prepareAndFinishRunShouldGuardPerPhaseAndGlobalModifyLease() {
        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);

        workbench.prepareRun(WorkbenchPhase.IMPLEMENT_TEST, "write-run-1",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(10));

        assertEquals("write-run-1", workbench.getActiveWriteRunReference().getRunId());
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS,
                workbench.phase(WorkbenchPhase.IMPLEMENT_TEST).getStatus());
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.prepareRun(WorkbenchPhase.IMPLEMENT_TEST, "write-run-2",
                        RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(11)));
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.prepareReviewRefactorRun(
                        "write-run-2", RunMode.MODIFY_WORKSPACE,
                        reviewConfirmation("review-confirmation-1", workbench), OWNER,
                        NOW.plusSeconds(12)));

        assertTrue(workbench.finishRun(
                WorkbenchPhase.IMPLEMENT_TEST, "write-run-1", NOW.plusSeconds(13)));
        assertNull(workbench.getActiveWriteRunReference());
        assertFalse(workbench.finishRun(
                WorkbenchPhase.IMPLEMENT_TEST, "write-run-1", NOW.plusSeconds(14)));
    }

    @Test
    void prepareRunShouldRejectStaleVersionBeforeChangingPhaseOrWriteLease() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST, "implementation-conversation",
                OWNER, NOW.plusSeconds(1));
        long currentVersion = workbench.getVersion();

        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.prepareRun(
                        WorkbenchPhase.IMPLEMENT_TEST, "stale-write-run",
                        RunMode.MODIFY_WORKSPACE, OWNER,
                        currentVersion - 1L, NOW.plusSeconds(2)));

        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, failure.getCode());
        assertEquals(currentVersion, workbench.getVersion());
        assertNull(workbench.getActiveWriteRunReference());
        assertNull(workbench.phase(WorkbenchPhase.IMPLEMENT_TEST)
                .getActiveRunReference());
        assertEquals(WorkbenchPhaseStatus.NOT_STARTED,
                workbench.phase(WorkbenchPhase.IMPLEMENT_TEST).getStatus());

        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, "current-write-run",
                RunMode.MODIFY_WORKSPACE, OWNER,
                currentVersion, NOW.plusSeconds(3));
        assertEquals("current-write-run",
                workbench.getActiveWriteRunReference().getRunId());
    }

    @Test
    void phaseRunPolicyShouldAllowModifyWorkspaceForAllPhasesWithoutReviewConfirmation() {
        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);

        workbench.prepareRun(WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(10));
        workbench.finishRun(WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1", NOW.plusSeconds(15));
        workbench.prepareRun(WorkbenchPhase.SOLUTION_DESIGN, "run-2",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(16));
        workbench.finishRun(WorkbenchPhase.SOLUTION_DESIGN, "run-2", NOW.plusSeconds(20));
        workbench.prepareRun(WorkbenchPhase.REVIEW_REFACTOR, "run-3",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(21));
        assertEquals("run-3",
                workbench.phase(WorkbenchPhase.REVIEW_REFACTOR)
                        .getActiveRunReference().getRunId());
    }

    @Test
    void reviewModifyConfirmationShouldBindOpinionVersionHashActorAndWorkbench() {
        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);
        ReviewOpinion opinion = ReviewOpinion.record(
                workbench.getId(), 3L, repeat('b'), OWNER, NOW.plusSeconds(5));
        ReviewModifyConfirmation confirmation = ReviewModifyConfirmation.confirm(
                "review-confirmation-3", opinion, OWNER, NOW.plusSeconds(6));

        workbench.prepareReviewRefactorRun(
                "review-run", RunMode.MODIFY_WORKSPACE, confirmation,
                OWNER, NOW.plusSeconds(7));

        ActiveRunReference active = workbench.phase(WorkbenchPhase.REVIEW_REFACTOR)
                .getActiveRunReference();
        assertEquals(3L, active.getReviewOpinionVersion().longValue());
        assertEquals(repeat('b'), active.getReviewOpinionHash());
        assertEquals(OWNER, confirmation.getConfirmedBy());
        assertEquals(WorkbenchPhase.REVIEW_REFACTOR, confirmation.getPhase());

        Workbench another = Workbench.create(
                WorkbenchId.of("workbench-2"), OWNER,
                "Another", "Another goal", AgentType.CODEX, "local",
                workbench.getRepositoryScope(), workbench.getCreationSnapshotReference(), NOW);
        bindAllConversations(another);
        assertThrows(WorkbenchDomainException.class,
                () -> another.prepareReviewRefactorRun(
                        "review-run-2", RunMode.MODIFY_WORKSPACE, confirmation,
                        OWNER, NOW.plusSeconds(8)));
    }

    @Test
    void reviewModifyConfirmationShouldRejectOpinionRecordedByAnotherActor() {
        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);
        OwnerReference anotherActor = OwnerReference.of("user-2", "Other");
        ReviewModifyConfirmation confirmation = ReviewModifyConfirmation.confirm(
                "review-confirmation-other-reviewer",
                ReviewOpinion.record(
                        workbench.getId(), 1L, repeat('d'), anotherActor,
                        NOW.plusSeconds(5)),
                OWNER, NOW.plusSeconds(6));

        assertThrows(WorkbenchDomainException.class,
                () -> workbench.prepareReviewRefactorRun(
                        "review-run-other-reviewer", RunMode.MODIFY_WORKSPACE,
                        confirmation, OWNER, NOW.plusSeconds(7)));
    }

    @Test
    void restoreShouldRejectImpossibleActiveRunStateAndMismatchedWriteLease() {
        ActiveRunReference activeRun = ActiveRunReference.restore(
                "write-run-restore", WorkbenchPhase.IMPLEMENT_TEST,
                RunMode.MODIFY_WORKSPACE, null, null, null,
                NOW.plusSeconds(2));

        assertThrows(IllegalArgumentException.class,
                () -> WorkbenchPhaseState.restore(
                        WorkbenchPhase.IMPLEMENT_TEST,
                        WorkbenchPhaseStatus.IN_PROGRESS,
                        Collections.<PhaseConversationReference>emptyList(),
                        0, activeRun, NOW.plusSeconds(2), null));

        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);
        workbench.prepareRun(
                WorkbenchPhase.IMPLEMENT_TEST, "write-run-restore",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(10));
        ActiveRunReference mismatchedLease = ActiveRunReference.restore(
                "write-run-restore", WorkbenchPhase.IMPLEMENT_TEST,
                RunMode.MODIFY_WORKSPACE, null, null, null,
                NOW.plusSeconds(11));

        assertThrows(IllegalArgumentException.class,
                () -> Workbench.restore(
                        workbench.getId(), workbench.getOwner(), workbench.getTitle(),
                        workbench.getOriginalGoal(), workbench.getAgentType(),
                        workbench.getEnvironment(), workbench.getRepositoryScope(),
                        workbench.getCreationSnapshotReference(), workbench.getPhases(),
                        mismatchedLease, workbench.getStatus(), workbench.getCreatedAt(),
                        workbench.getUpdatedAt(), workbench.getVersion()));
    }

    @Test
    void restoreShouldRejectConversationCreatedOutsideWorkbenchOwnership() {
        Workbench created = newWorkbench();
        OwnerReference anotherActor = OwnerReference.of("user-2", "Other");
        WorkbenchPhaseState corruptedAnalysis = WorkbenchPhaseState.restore(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                WorkbenchPhaseStatus.NOT_STARTED,
                Collections.singletonList(
                        PhaseConversationReference.active(
                                "foreign-conversation", 0, anotherActor,
                                NOW.plusSeconds(1))),
                0, null, NOW.plusSeconds(1), null);

        assertThrows(IllegalArgumentException.class,
                () -> Workbench.restore(
                        created.getId(), created.getOwner(), created.getTitle(),
                        created.getOriginalGoal(), created.getAgentType(),
                        created.getEnvironment(), created.getRepositoryScope(),
                        created.getCreationSnapshotReference(),
                        Arrays.asList(
                                corruptedAnalysis,
                                WorkbenchPhaseState.initial(
                                        WorkbenchPhase.SOLUTION_DESIGN),
                                WorkbenchPhaseState.initial(
                                        WorkbenchPhase.IMPLEMENT_TEST),
                                WorkbenchPhaseState.initial(
                                        WorkbenchPhase.REVIEW_REFACTOR)),
                        null, WorkbenchStatus.ACTIVE,
                        created.getCreatedAt(), NOW.plusSeconds(1), 3L));
    }

    @Test
    void activeRunRestoreShouldPreserveReviewProofAndRejectPartialOrForbiddenFacts() {
        ActiveRunReference restored = ActiveRunReference.restore(
                "review-run-restored", WorkbenchPhase.REVIEW_REFACTOR,
                RunMode.MODIFY_WORKSPACE, "confirmation-1", Long.valueOf(4L),
                repeat('e'), NOW.plusSeconds(1));

        assertEquals("confirmation-1", restored.getReviewConfirmationId());
        assertEquals(4L, restored.getReviewOpinionVersion().longValue());
        assertEquals(repeat('e'), restored.getReviewOpinionHash());
        assertThrows(IllegalArgumentException.class,
                () -> ActiveRunReference.restore(
                        "review-run-partial", WorkbenchPhase.REVIEW_REFACTOR,
                        RunMode.MODIFY_WORKSPACE, "confirmation-1", null,
                        repeat('e'), NOW.plusSeconds(1)));
        ActiveRunReference analysisWrite = ActiveRunReference.restore(
                "analysis-write", WorkbenchPhase.REQUIREMENT_ANALYSIS,
                RunMode.MODIFY_WORKSPACE, null, null, null,
                NOW.plusSeconds(1));
        assertEquals(RunMode.MODIFY_WORKSPACE, analysisWrite.getRunMode());
        assertThrows(WorkbenchDomainException.class,
                () -> ActiveRunReference.restore(
                        "implementation-with-review-proof",
                        WorkbenchPhase.IMPLEMENT_TEST, RunMode.MODIFY_WORKSPACE,
                        "confirmation-1", Long.valueOf(4L), repeat('e'),
                        NOW.plusSeconds(1)));
    }

    @Test
    void completeAndReopenShouldBeHumanStateOnlyAndRejectActiveRun() {
        Workbench workbench = newWorkbench();
        workbench.bindConversation(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "conversation-1", OWNER, NOW.plusSeconds(1));
        workbench.prepareRun(WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1",
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(2));

        assertThrows(WorkbenchDomainException.class,
                () -> workbench.completePhase(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(3)));
        workbench.finishRun(WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1",
                NOW.plusSeconds(4));
        assertTrue(workbench.completePhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(5)));
        assertFalse(workbench.completePhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(6)));
        assertEquals(WorkbenchPhaseStatus.HUMAN_COMPLETED,
                workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS).getStatus());
        assertTrue(workbench.reopenPhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(7)));
        assertEquals(WorkbenchPhaseStatus.IN_PROGRESS,
                workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS).getStatus());
    }

    @Test
    void reviewShouldAllowManualCompletionWithoutStartingConversation() {
        Workbench workbench = newWorkbench();

        assertTrue(workbench.completePhase(
                WorkbenchPhase.REVIEW_REFACTOR, OWNER,
                NOW.plusSeconds(1)));
        assertEquals(WorkbenchPhaseStatus.HUMAN_COMPLETED,
                workbench.phase(WorkbenchPhase.REVIEW_REFACTOR).getStatus());
        assertEquals(NOW.plusSeconds(1), workbench.phase(
                WorkbenchPhase.REVIEW_REFACTOR).getCompletedAt());

        assertTrue(workbench.reopenPhase(
                WorkbenchPhase.REVIEW_REFACTOR, OWNER,
                NOW.plusSeconds(2)));
        assertEquals(WorkbenchPhaseStatus.NOT_STARTED,
                workbench.phase(WorkbenchPhase.REVIEW_REFACTOR).getStatus());
        assertNull(workbench.phase(
                WorkbenchPhase.REVIEW_REFACTOR).currentConversation());
    }

    @Test
    void ownerAndArchivedRulesShouldBeEnforcedInsideAggregate() {
        Workbench workbench = newWorkbench();
        OwnerReference stranger = OwnerReference.of("user-2", "Other");

        assertThrows(WorkbenchDomainException.class,
                () -> workbench.bindConversation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", stranger,
                        NOW.plusSeconds(1)));

        assertTrue(workbench.archive(OWNER, NOW.plusSeconds(2)));
        assertFalse(workbench.archive(OWNER, NOW.plusSeconds(3)));
        assertThrows(WorkbenchDomainException.class,
                () -> workbench.bindConversation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                        NOW.plusSeconds(4)));
    }

    @Test
    void lifecycleMutationsShouldValidateExpectedVersionInsideAggregate() {
        Workbench workbench = newWorkbench();

        assertVersionConflict(() -> workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                1L, NOW.plusSeconds(1)));
        assertVersionConflict(() -> workbench.restartConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-2", OWNER,
                1L, NOW.plusSeconds(1)));
        assertVersionConflict(() -> workbench.completePhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER,
                1L, NOW.plusSeconds(1)));
        assertVersionConflict(() -> workbench.reopenPhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER,
                1L, NOW.plusSeconds(1)));
        assertVersionConflict(() -> workbench.archive(
                OWNER, 1L, NOW.plusSeconds(1)));

        assertEquals(0L, workbench.getVersion());
        assertTrue(workbench.phase(WorkbenchPhase.REQUIREMENT_ANALYSIS)
                .getConversationHistory().isEmpty());
        assertEquals(WorkbenchStatus.ACTIVE, workbench.getStatus());
    }

    @Test
    void ownerOnlyCheckShouldAllowArchivedOwnerAndRejectForeignActor() {
        Workbench workbench = newWorkbench();
        OwnerReference stranger = OwnerReference.of("user-2", "Other");

        assertDoesNotThrow(() -> workbench.requireOwnedBy(OWNER));
        WorkbenchDomainException activeError = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.requireOwnedBy(stranger));
        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED, activeError.getCode());

        workbench.archive(OWNER, NOW.plusSeconds(1));

        assertDoesNotThrow(() -> workbench.requireOwnedBy(OWNER));
        WorkbenchDomainException archivedError = assertThrows(
                WorkbenchDomainException.class,
                () -> workbench.requireOwnedBy(stranger));
        assertEquals(WorkbenchErrorCode.OWNER_REQUIRED, archivedError.getCode());
    }

    @Test
    void archiveShouldRejectActiveModifyRun() {
        Workbench workbench = newWorkbench();
        bindAllConversations(workbench);
        workbench.prepareRun(WorkbenchPhase.IMPLEMENT_TEST, "write-run-1",
                RunMode.MODIFY_WORKSPACE, OWNER, NOW.plusSeconds(10));

        assertThrows(WorkbenchDomainException.class,
                () -> workbench.archive(OWNER, NOW.plusSeconds(11)));
    }

    @Test
    void successfulMutationsShouldAdvanceVersionWhileIdempotentNoOpsDoNot() {
        Workbench workbench = newWorkbench();

        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                NOW.plusSeconds(1));
        assertEquals(1L, workbench.getVersion());
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-1", OWNER,
                NOW.plusSeconds(2));
        assertEquals(1L, workbench.getVersion());

        workbench.prepareRun(WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1",
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(3));
        assertEquals(2L, workbench.getVersion());
        workbench.finishRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1", NOW.plusSeconds(4));
        assertEquals(3L, workbench.getVersion());
        workbench.finishRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "run-1", NOW.plusSeconds(5));
        assertEquals(3L, workbench.getVersion());

        workbench.completePhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(6));
        assertEquals(4L, workbench.getVersion());
        workbench.completePhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(7));
        assertEquals(4L, workbench.getVersion());
        workbench.reopenPhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(8));
        assertEquals(5L, workbench.getVersion());
        workbench.reopenPhase(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, OWNER, NOW.plusSeconds(9));
        assertEquals(5L, workbench.getVersion());

        workbench.restartConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "conversation-2", OWNER,
                NOW.plusSeconds(10));
        assertEquals(6L, workbench.getVersion());
        workbench.archive(OWNER, NOW.plusSeconds(11));
        assertEquals(7L, workbench.getVersion());
    }

    @Test
    void restoredWorkbenchShouldAdvanceFromPersistedVersion() {
        Workbench created = newWorkbench();
        Workbench restored = Workbench.restore(
                created.getId(), created.getOwner(), created.getTitle(),
                created.getOriginalGoal(), created.getAgentType(), created.getEnvironment(),
                created.getRepositoryScope(), created.getCreationSnapshotReference(),
                created.getPhases(), null, WorkbenchStatus.ACTIVE,
                created.getCreatedAt(), created.getUpdatedAt(), 9L);

        restored.bindConversation(
                WorkbenchPhase.SOLUTION_DESIGN, "conversation-restored", OWNER,
                NOW.plusSeconds(1));

        assertEquals(10L, restored.getVersion());
    }

    private static Workbench newWorkbench() {
        RepositoryScope repositoryScope = WorkbenchDomainFixtures.repositoryScope();
        return Workbench.create(
                WorkbenchId.of("workbench-1"), OWNER,
                "Workbench MVP", "实现本地开发工作台", AgentType.CODEX, "local",
                repositoryScope,
                WorkbenchDomainFixtures.snapshotReference("snapshot-1", repeat('a')),
                NOW);
    }

    private static void bindAllConversations(Workbench workbench) {
        int offset = 1;
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            workbench.bindConversation(phase, "conversation-" + phase.name(), OWNER,
                    NOW.plusSeconds(offset++));
        }
    }

    private static ReviewModifyConfirmation reviewConfirmation(
            String confirmationId, Workbench workbench) {
        ReviewOpinion opinion = ReviewOpinion.record(
                workbench.getId(), 1L, repeat('c'), OWNER, NOW.plusSeconds(5));
        return ReviewModifyConfirmation.confirm(
                confirmationId, opinion, OWNER, NOW.plusSeconds(6));
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }

    private static void assertVersionConflict(Executable mutation) {
        WorkbenchDomainException error = assertThrows(
                WorkbenchDomainException.class, mutation);
        assertEquals(WorkbenchErrorCode.VERSION_CONFLICT, error.getCode());
    }
}
