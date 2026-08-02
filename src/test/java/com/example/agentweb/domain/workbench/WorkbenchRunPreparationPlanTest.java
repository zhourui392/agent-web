package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.workspace.RepositoryScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run 准备计划的 Phase、RunMode、Handoff 与写根不变量测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunPreparationPlanTest {

    private static final Instant NOW =
            Instant.parse("2026-08-01T09:00:00Z");
    private static final OwnerReference OWNER =
            OwnerReference.of("owner-1", "Alex");

    private Workbench workbench;

    @BeforeEach
    void setUp() {
        RepositoryScope scope = WorkbenchDomainFixtures.repositoryScope();
        workbench = Workbench.create(
                WorkbenchId.of("workbench-plan-1"), OWNER,
                "Workbench", "实现本地工作台", AgentType.CODEX,
                "local", scope,
                WorkbenchDomainFixtures.snapshotReference(
                        "creation-snapshot", repeat('1')),
                NOW);
        workbench.bindConversation(
                WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "session-analysis", OWNER, NOW.plusSeconds(1));
        workbench.bindConversation(
                WorkbenchPhase.SOLUTION_DESIGN,
                "session-design", OWNER, NOW.plusSeconds(2));
        workbench.bindConversation(
                WorkbenchPhase.IMPLEMENT_TEST,
                "session-implement", OWNER, NOW.plusSeconds(3));
        workbench.bindConversation(
                WorkbenchPhase.REVIEW_REFACTOR,
                "session-review", OWNER, NOW.plusSeconds(4));
    }

    @Test
    void analysisAndDesignShouldRejectModifyModeInDomain() {
        assertCode(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        RunMode.MODIFY_WORKSPACE, null, null, OWNER,
                        workbench.getVersion()));
        assertCode(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.SOLUTION_DESIGN,
                        RunMode.MODIFY_WORKSPACE, Long.valueOf(0L), null,
                        OWNER, workbench.getVersion()));
    }

    @Test
    void firstPhaseShouldRejectHandoffAndDownstreamShouldRequireVersion() {
        assertCode(
                WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        RunMode.DISCUSS_READ_ONLY, Long.valueOf(0L), null,
                        OWNER, workbench.getVersion()));
        assertCode(
                WorkbenchErrorCode.PHASE_TRANSITION_INVALID,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.SOLUTION_DESIGN,
                        RunMode.DISCUSS_READ_ONLY, null, null, OWNER,
                        workbench.getVersion()));
    }

    @Test
    void downstreamPreparationShouldCreateInitialReceptionFromExactLatestHandoff() {
        WorkbenchRunPreparationPlan plan = workbench.planRunPreparation(
                WorkbenchPhase.SOLUTION_DESIGN,
                RunMode.DISCUSS_READ_ONLY, Long.valueOf(0L), null,
                OWNER, workbench.getVersion());
        PhaseHandoff latest = PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "人工确认的需求交接",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(5));

        HandoffReception reception = plan.resolveHandoffReception(
                null, latest, OWNER, NOW.plusSeconds(6));

        assertEquals(workbench.getId(), reception.getWorkbenchId());
        assertEquals(WorkbenchPhase.SOLUTION_DESIGN,
                reception.getTargetPhase());
        assertEquals(WorkbenchPhase.REQUIREMENT_ANALYSIS,
                reception.getSourcePhase());
        assertEquals(0L, reception.getSourceVersion());
        assertEquals(latest.getContentHash(), reception.getSourceHash());
        assertEquals(OWNER, reception.getAcceptedBy());
        assertEquals(NOW.plusSeconds(6), reception.getAcceptedAt());
    }

    @Test
    void initialReceptionShouldRejectSourceChangedAfterClientPreview() {
        WorkbenchRunPreparationPlan plan = workbench.planRunPreparation(
                WorkbenchPhase.SOLUTION_DESIGN,
                RunMode.DISCUSS_READ_ONLY, Long.valueOf(0L), null,
                OWNER, workbench.getVersion());
        PhaseHandoff latest = PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "初始交接",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(5));
        latest.update(
                0L, "预览后更新的交接",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(6));

        assertCode(
                WorkbenchErrorCode.VERSION_CONFLICT,
                () -> plan.resolveHandoffReception(
                        null, latest, OWNER, NOW.plusSeconds(7)));
    }

    @Test
    void existingReceptionShouldKeepHistoricalSourceWhenLatestChanges() {
        WorkbenchRunPreparationPlan plan = workbench.planRunPreparation(
                WorkbenchPhase.SOLUTION_DESIGN,
                RunMode.DISCUSS_READ_ONLY, Long.valueOf(0L), null,
                OWNER, workbench.getVersion());
        HandoffReception accepted = HandoffReception.accept(
                workbench.getId(), WorkbenchPhase.SOLUTION_DESIGN,
                WorkbenchPhase.REQUIREMENT_ANALYSIS, 0L, repeat('a'),
                OWNER, NOW.plusSeconds(5));
        PhaseHandoff latest = PhaseHandoff.create(
                workbench.getId(), WorkbenchPhase.REQUIREMENT_ANALYSIS,
                "更新后的上游交接",
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                workbench.getRepositoryScope(), OWNER, NOW.plusSeconds(6));

        assertSame(accepted, plan.resolveHandoffReception(
                accepted, latest, OWNER, NOW.plusSeconds(7)));
    }

    @Test
    void implementModifyShouldFreezeAllSelectedRepositoriesAsWritable() {
        WorkbenchRunPreparationPlan plan = workbench.planRunPreparation(
                WorkbenchPhase.IMPLEMENT_TEST,
                RunMode.MODIFY_WORKSPACE, Long.valueOf(0L), null, OWNER,
                workbench.getVersion());

        assertEquals(
                WorkbenchRunPreparationPlan.WorkspaceAccess.WORKSPACE_WRITE,
                plan.getWorkspaceAccess());
        assertEquals(2, plan.getReadableRepositoryRoots().size());
        assertEquals(plan.getReadableRepositoryRoots(),
                plan.getWritableRepositoryRoots());
        assertEquals(2, plan.getWritableRepositoryKeys().size());
    }

    @Test
    void reviewModifyShouldRequireExplicitConfirmationId() {
        assertCode(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.REVIEW_REFACTOR,
                        RunMode.MODIFY_WORKSPACE, Long.valueOf(0L), null,
                        OWNER, workbench.getVersion()));

        WorkbenchRunPreparationPlan plan = workbench.planRunPreparation(
                WorkbenchPhase.REVIEW_REFACTOR,
                RunMode.MODIFY_WORKSPACE, Long.valueOf(0L),
                "confirmation-1", OWNER, workbench.getVersion());

        assertTrue(plan.requiresReviewConfirmation());
        assertEquals("confirmation-1", plan.getReviewConfirmationId());

        ReviewOpinion opinion = ReviewOpinion.record(
                workbench.getId(), 1L, repeat('6'), OWNER,
                NOW.plusSeconds(4));
        ReviewModifyConfirmation confirmation =
                ReviewModifyConfirmation.confirm(
                        "confirmation-1", opinion, OWNER,
                        NOW.plusSeconds(5));
        assertSame(confirmation, plan.requireReviewProof(
                confirmation, opinion, OWNER, NOW.plusSeconds(6)));

        ReviewOpinion wrongOpinion = ReviewOpinion.record(
                workbench.getId(), 2L, repeat('7'), OWNER,
                NOW.plusSeconds(4));
        assertCode(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                () -> plan.requireReviewProof(
                        confirmation, wrongOpinion, OWNER,
                        NOW.plusSeconds(6)));
    }

    @Test
    void preparationShouldRejectActivePhaseRunBeforeExternalWork() {
        workbench.prepareRun(
                WorkbenchPhase.REQUIREMENT_ANALYSIS, "active-run",
                RunMode.DISCUSS_READ_ONLY, OWNER, NOW.plusSeconds(5));

        assertCode(
                WorkbenchErrorCode.PHASE_RUN_ACTIVE,
                () -> workbench.planRunPreparation(
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        RunMode.DISCUSS_READ_ONLY, null, null, OWNER,
                        workbench.getVersion()));
    }

    private static void assertCode(
            WorkbenchErrorCode code, Runnable action) {
        WorkbenchDomainException failure = assertThrows(
                WorkbenchDomainException.class, action::run);
        assertEquals(code, failure.getCode());
    }

    private static String repeat(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
