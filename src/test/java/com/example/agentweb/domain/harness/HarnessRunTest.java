package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 聚合状态机、不变量和失效传播测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
class HarnessRunTest {

    private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");

    @Test
    void create_should_build_independent_four_stage_draft() {
        HarnessRun run = newRun();

        assertEquals(HarnessRunStatus.DRAFT, run.getStatus());
        assertEquals(4, run.getStages().size());
        for (HarnessStage stage : HarnessStage.values()) {
            assertEquals(StageStatus.PENDING, run.stage(stage).getStatus());
            assertTrue(run.stage(stage).getAttempts().isEmpty());
        }
        assertEquals("RUN_CREATED", run.getEvents().get(0).getEventType());
    }

    @Test
    void start_should_enforce_stage_order_single_writer_and_idempotency() {
        HarnessRun run = newRun();

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.startStage(HarnessStage.DESIGN, "start-design", NOW.plusSeconds(1)));

        assertTrue(run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1)));
        assertFalse(run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(2)));
        assertEquals(HarnessRunStatus.ACTIVE, run.getStatus());
        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        assertEquals(1, run.stage(HarnessStage.ANALYSIS).currentAttempt().getNumber());

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.startStage(HarnessStage.DESIGN, "another-write", NOW.plusSeconds(3)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.startStage(HarnessStage.ANALYSIS, "different-key", NOW.plusSeconds(3)));
    }

    @Test
    void approval_should_be_bound_to_current_artifact_baseline_hash() {
        HarnessRun run = newRun();
        prepareForApproval(run, HarnessStage.ANALYSIS, 1L);
        String currentHash = run.currentArtifactBaselineHash(HarnessStage.ANALYSIS);

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.approve(HarnessStage.ANALYSIS, "approval-1", repeat('f'),
                        "admin", "looks good", NOW.plusSeconds(90)));

        assertTrue(run.approve(HarnessStage.ANALYSIS, "approval-1", currentHash,
                "admin", "looks good", NOW.plusSeconds(91)));
        assertFalse(run.approve(HarnessStage.ANALYSIS, "approval-1", currentHash,
                "admin", "looks good", NOW.plusSeconds(92)));
        assertEquals(StageStatus.PASSED, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(HarnessRunStatus.ACTIVE, run.getStatus());
        assertTrue(run.getApprovals().get(0).isValid());
    }

    @Test
    void failed_gate_and_retry_should_create_new_attempt_without_overwriting_evidence() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start-1", NOW.plusSeconds(1));
        registerRequiredArtifacts(run, HarnessStage.ANALYSIS, 2L);

        assertTrue(run.recordGate(HarnessStage.ANALYSIS, "gate-failed",
                "required-artifacts-present", false, Collections.singletonList("artifact-1"),
                "missing index", NOW.plusSeconds(20)));
        assertEquals(StageStatus.FAILED, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(HarnessRunStatus.FAILED, run.getStatus());

        assertTrue(run.retryStage(HarnessStage.ANALYSIS, "retry-1", NOW.plusSeconds(21)));
        assertFalse(run.retryStage(HarnessStage.ANALYSIS, "retry-1", NOW.plusSeconds(22)));
        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(HarnessRunStatus.ACTIVE, run.getStatus());
        assertEquals(2, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        assertEquals(StageAttemptStatus.FAILED,
                run.stage(HarnessStage.ANALYSIS).getAttempts().get(0).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                run.stage(HarnessStage.ANALYSIS).getAttempts().get(1).getStatus());
        assertEquals(1, run.getGateResults().size());
    }

    @Test
    void revising_passed_upstream_stage_should_invalidate_downstream_and_old_approvals() {
        HarnessRun run = newRun();
        passStage(run, HarnessStage.ANALYSIS, 1L);
        passStage(run, HarnessStage.DESIGN, 100L);

        assertEquals(StageStatus.PASSED, run.stage(HarnessStage.DESIGN).getStatus());
        assertTrue(run.getApprovals().stream().allMatch(Approval::isValid));

        run.retryStage(HarnessStage.ANALYSIS, "revise-analysis", NOW.plusSeconds(500));

        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageStatus.INVALIDATED, run.stage(HarnessStage.DESIGN).getStatus());
        assertTrue(run.getApprovals().stream().noneMatch(Approval::isValid));
        assertTrue(run.getEvents().stream().anyMatch(event -> "STAGE_INVALIDATED".equals(event.getEventType())));
    }

    @Test
    void rejection_should_resume_same_attempt_and_retain_decision_history() {
        HarnessRun run = newRun();
        prepareForApproval(run, HarnessStage.ANALYSIS, 1L);
        String baselineHash = run.currentArtifactBaselineHash(HarnessStage.ANALYSIS);

        assertTrue(run.reject(HarnessStage.ANALYSIS, "reject-1", baselineHash,
                "admin", "revise AC", NOW.plusSeconds(90)));
        assertFalse(run.reject(HarnessStage.ANALYSIS, "reject-1", baselineHash,
                "admin", "revise AC", NOW.plusSeconds(91)));

        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        assertEquals(ApprovalDecision.REJECTED, run.getApprovals().get(0).getDecision());
    }

    @Test
    void cancel_should_be_terminal_and_forbid_ordinary_actions() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));

        assertTrue(run.cancel("admin", "no longer needed", NOW.plusSeconds(2)));
        assertFalse(run.cancel("admin", "duplicate", NOW.plusSeconds(3)));
        assertEquals(HarnessRunStatus.CANCELLED, run.getStatus());
        assertEquals(StageStatus.CANCELLED, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.retryStage(HarnessStage.ANALYSIS, "retry", NOW.plusSeconds(4)));
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.startStage(HarnessStage.ANALYSIS, "start-again", NOW.plusSeconds(4)));
    }

    @Test
    void approving_deployment_should_complete_run_and_lock_all_stages() {
        HarnessRun run = newRun();
        long offset = 1L;
        for (HarnessStage stage : HarnessStage.values()) {
            passStage(run, stage, offset);
            offset += 100L;
        }

        assertEquals(HarnessRunStatus.COMPLETED, run.getStatus());
        assertTrue(run.isTerminal());
        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.retryStage(HarnessStage.DESIGN, "late-retry", NOW.plusSeconds(999)));
    }

    @Test
    void artifact_registration_should_version_immutably_and_reset_waiting_approval() {
        HarnessRun run = newRun();
        prepareForApproval(run, HarnessStage.ANALYSIS, 1L);

        ArtifactDescriptor versionTwo = run.registerArtifact(
                HarnessStage.ANALYSIS,
                "requirement-v2",
                ArtifactType.REQUIREMENT,
                ArtifactContent.from("v2".getBytes(StandardCharsets.UTF_8)),
                "text/markdown",
                ArtifactClassification.INTERNAL,
                "admin",
                Collections.<ArtifactReference>emptyList(),
                NOW.plusSeconds(90));

        assertEquals(2, versionTwo.getVersion());
        assertEquals(StageStatus.RUNNING, run.stage(HarnessStage.ANALYSIS).getStatus());
        assertEquals(2, run.artifactVersions(ArtifactType.REQUIREMENT).size());
        assertThrows(UnsupportedOperationException.class,
                () -> run.getArtifacts().add(versionTwo));
    }

    @Test
    void conversation_should_start_pending_stage_record_message_and_be_idempotent() {
        HarnessRun run = newRun();

        StageConversationTurn created = run.prepareConversationTurn(
                HarnessStage.ANALYSIS, "conversation-1", "请先梳理验收标准",
                "admin", NOW.plusSeconds(1));
        StageConversationTurn duplicated = run.prepareConversationTurn(
                HarnessStage.ANALYSIS, "conversation-1", "请先梳理验收标准",
                "admin", NOW.plusSeconds(2));

        assertEquals(1, created.getAttemptNumber());
        assertTrue(created.isAttemptOpened());
        assertFalse(created.isDuplicated());
        assertTrue(duplicated.isDuplicated());
        assertEquals(1, run.stage(HarnessStage.ANALYSIS).getAttempts().size());
        assertEquals(1L, run.getEvents().stream()
                .filter(event -> "STAGE_CONVERSATION_MESSAGE".equals(event.getEventType()))
                .count());
        assertTrue(run.getEvents().stream()
                .anyMatch(event -> event.getDetail().endsWith("\n请先梳理验收标准")));
    }

    @Test
    void conversation_should_supersede_completed_runtime_attempt_and_preserve_artifacts() {
        HarnessRun run = newRun();
        run.startStage(HarnessStage.ANALYSIS, "start-analysis", NOW.plusSeconds(1));
        completeRuntime(run, HarnessStage.ANALYSIS, "exec-1", 2L);
        int existingArtifacts = run.getArtifacts().size();

        StageConversationTurn turn = run.prepareConversationTurn(
                HarnessStage.ANALYSIS, "conversation-revision", "补充并发场景，再调整方案",
                "admin", NOW.plusSeconds(30));

        assertEquals(2, turn.getAttemptNumber());
        assertTrue(turn.isAttemptOpened());
        assertEquals(StageAttemptStatus.SUPERSEDED,
                run.stage(HarnessStage.ANALYSIS).getAttempts().get(0).getStatus());
        assertEquals(StageAttemptStatus.RUNNING,
                run.stage(HarnessStage.ANALYSIS).currentAttempt().getStatus());
        assertEquals(existingArtifacts, run.getArtifacts().size());
        assertEquals("exec-1", run.stage(HarnessStage.ANALYSIS)
                .getAttempts().get(0).getExecutionId());
    }

    @Test
    void conversation_revision_should_invalidate_approved_stage_and_downstream() {
        HarnessRun run = newRun();
        passStage(run, HarnessStage.ANALYSIS, 1L);
        passStage(run, HarnessStage.DESIGN, 100L);

        StageConversationTurn turn = run.prepareConversationTurn(
                HarnessStage.ANALYSIS, "revise-analysis", "修改需求边界",
                "admin", NOW.plusSeconds(300));

        assertEquals(2, turn.getAttemptNumber());
        assertEquals(StageAttemptStatus.SUPERSEDED,
                run.stage(HarnessStage.ANALYSIS).getAttempts().get(0).getStatus());
        assertEquals(StageStatus.INVALIDATED, run.stage(HarnessStage.DESIGN).getStatus());
        assertTrue(run.getApprovals().stream().noneMatch(Approval::isValid));
    }

    @Test
    void conversation_should_reject_parallel_runtime_and_waiting_input() {
        HarnessRun running = newRun();
        running.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));
        bindExecution(running, HarnessStage.ANALYSIS, "exec-running", 2L);

        assertThrows(IllegalHarnessTransitionException.class,
                () -> running.prepareConversationTurn(HarnessStage.ANALYSIS,
                        "parallel", "运行中继续修改", "admin", NOW.plusSeconds(3)));

        HarnessRun waiting = newRun();
        waiting.startStage(HarnessStage.ANALYSIS, "start", NOW.plusSeconds(1));
        waiting.requestInput(HarnessStage.ANALYSIS, "question-1", "需要哪个租户？",
                true, "agent", NOW.plusSeconds(2));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> waiting.prepareConversationTurn(HarnessStage.ANALYSIS,
                        "wrong-answer", "租户 A", "admin", NOW.plusSeconds(3)));
    }

    @Test
    void conversation_idempotency_key_should_not_accept_different_message() {
        HarnessRun run = newRun();
        run.prepareConversationTurn(HarnessStage.ANALYSIS, "conversation-1",
                "第一条消息", "admin", NOW.plusSeconds(1));

        assertThrows(IllegalHarnessTransitionException.class,
                () -> run.prepareConversationTurn(HarnessStage.ANALYSIS,
                        "conversation-1", "不同消息", "admin", NOW.plusSeconds(2)));
    }

    private HarnessRun newRun() {
        return HarnessRun.create(
                "run-1",
                "Build M1",
                "/workspace/agent-web",
                "CODEX",
                "local",
                "harness@1.0.0",
                "admin",
                "create-1",
                StageContract.mvpDefaults(),
                NOW);
    }

    private void passStage(HarnessRun run, HarnessStage stage, long offset) {
        prepareForApproval(run, stage, offset);
        run.approve(stage, "approval-" + stage.name(), run.currentArtifactBaselineHash(stage),
                "admin", "approved", NOW.plusSeconds(offset + 90L));
    }

    private void prepareForApproval(HarnessRun run, HarnessStage stage, long offset) {
        run.startStage(stage, "start-" + stage.name(), NOW.plusSeconds(offset));
        registerRequiredArtifacts(run, stage, offset + 1L);
        for (String rule : run.stage(stage).getContract().getDeterministicGates()) {
            run.recordGate(stage, "gate-" + stage.name() + "-" + rule, rule, true,
                    Collections.<String>emptyList(), null, NOW.plusSeconds(offset + 50L));
        }
        run.submitForApproval(stage, NOW.plusSeconds(offset + 80L));
    }

    private void registerRequiredArtifacts(HarnessRun run, HarnessStage stage, long offset) {
        int index = 0;
        for (ArtifactType type : run.stage(stage).getContract().getRequiredOutputArtifacts()) {
            run.registerArtifact(
                    stage,
                    "artifact-" + stage.name() + "-" + type.name(),
                    type,
                    ArtifactContent.from((stage.name() + type.name()).getBytes(StandardCharsets.UTF_8)),
                    "text/markdown",
                    ArtifactClassification.INTERNAL,
                    "admin",
                    Collections.<ArtifactReference>emptyList(),
                    NOW.plusSeconds(offset + index));
            index++;
        }
    }

    private void completeRuntime(HarnessRun run, HarnessStage stage,
                                 String executionId, long offset) {
        ExecutionReference reference = bindExecution(run, stage, executionId, offset);
        registerRequiredArtifacts(run, stage, offset + 1L);
        run.recordExecutionSucceeded(reference, NOW.plusSeconds(offset + 20L));
    }

    private ExecutionReference bindExecution(HarnessRun run, HarnessStage stage,
                                             String executionId, long offset) {
        int attempt = run.stage(stage).currentAttempt().getNumber();
        CapabilitySnapshotReference snapshot = new CapabilitySnapshotReference(
                run.getId(), stage, attempt, repeat('a'), repeat('b'),
                Collections.<String>emptySet());
        run.authorizeExecution(stage, snapshot);
        ExecutionReference reference = new ExecutionReference(
                executionId, run.getId(), stage, attempt, repeat('a'));
        run.bindExecution(reference, NOW.plusSeconds(offset));
        return reference;
    }

    private String repeat(char character) {
        StringBuilder value = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            value.append(character);
        }
        return value.toString();
    }
}
