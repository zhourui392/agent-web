package com.example.agentweb.domain.requirement;

import com.example.agentweb.domain.verification.VerificationOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requirement 聚合状态机测试：detailed-design §1.2 迁移表 T1—T15 逐条对应。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementTest {

    private static final String OWNER = "V33215020";
    private static final String OTHER_USER = "V99999999";

    // ==== T1/T2: attachPlan ====

    @Test
    public void t1_attachPlan_from_intake_should_enter_planned_and_record_event() {
        Requirement requirement = newIntakeRequirement();
        requirement.pullEvents();

        requirement.attachPlan(plan("do X then Y"), OWNER);

        assertEquals(RequirementStatus.PLANNED, requirement.getStatus());
        assertEquals("do X then Y", requirement.getPlan().getPlanText());
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_PLAN_ATTACHED, events.get(0).getEventType());
        assertEquals(RequirementStatus.INTAKE, events.get(0).getFromStatus());
        assertEquals(RequirementStatus.PLANNED, events.get(0).getToStatus());
    }

    @Test
    public void t1_attachPlan_with_blank_text_should_reject() {
        Requirement requirement = newIntakeRequirement();

        assertThrows(IllegalArgumentException.class, () -> requirement.attachPlan(plan("  "), OWNER));
        assertThrows(IllegalArgumentException.class, () -> requirement.attachPlan(null, OWNER));
        assertEquals(RequirementStatus.INTAKE, requirement.getStatus());
    }

    @Test
    public void t2_attachPlan_from_planned_should_replace_and_record_plan_replaced() {
        Requirement requirement = newIntakeRequirement();
        requirement.attachPlan(plan("v1"), OWNER);
        requirement.pullEvents();

        requirement.attachPlan(plan("v2"), OWNER);

        assertEquals(RequirementStatus.PLANNED, requirement.getStatus());
        assertEquals("v2", requirement.getPlan().getPlanText());
        assertEquals(RequirementEvent.TYPE_PLAN_REPLACED, requirement.pullEvents().get(0).getEventType());
    }

    // ==== T3: rejectPlan ====

    @Test
    public void t3_rejectPlan_should_return_to_intake_with_reason() {
        Requirement requirement = plannedRequirement();
        requirement.pullEvents();

        requirement.rejectPlan(OWNER, "方案不完整");

        assertEquals(RequirementStatus.INTAKE, requirement.getStatus());
        RequirementEvent event = requirement.pullEvents().get(0);
        assertEquals(RequirementEvent.TYPE_PLAN_REJECTED, event.getEventType());
        assertEquals("方案不完整", event.getDetail());
    }

    @Test
    public void t3_rejectPlan_without_reason_should_reject() {
        Requirement requirement = plannedRequirement();

        assertThrows(IllegalArgumentException.class, () -> requirement.rejectPlan(OWNER, " "));
    }

    // ==== T4: approve ====

    @Test
    public void t4_approve_by_owner_should_enter_approved() {
        Requirement requirement = plannedRequirement();
        requirement.pullEvents();

        requirement.approve(OWNER);

        assertEquals(RequirementStatus.APPROVED, requirement.getStatus());
        assertEquals(RequirementEvent.TYPE_APPROVED, requirement.pullEvents().get(0).getEventType());
    }

    @Test
    public void t4_approve_by_non_owner_should_reject() {
        Requirement requirement = plannedRequirement();

        assertThrows(ApprovalNotAllowedException.class, () -> requirement.approve(OTHER_USER));
        assertEquals(RequirementStatus.PLANNED, requirement.getStatus());
    }

    @Test
    public void t4_approve_without_plan_should_reject() {
        Requirement requirement = rebuild(RequirementStatus.PLANNED, null, null, null);

        assertThrows(PlanRequiredException.class, () -> requirement.approve(OWNER));
    }

    // ==== T5: attachWorkspace ====

    @Test
    public void t5_attachWorkspace_only_in_approved_and_status_unchanged() {
        Requirement requirement = approvedRequirement();
        requirement.pullEvents();

        requirement.attachWorkspace("W-R2607040001");

        assertEquals(RequirementStatus.APPROVED, requirement.getStatus());
        assertEquals("W-R2607040001", requirement.getWorkspaceId());
        assertEquals(RequirementEvent.TYPE_WORKSPACE_ATTACHED, requirement.pullEvents().get(0).getEventType());

        Requirement intake = newIntakeRequirement();
        assertThrows(IllegalRequirementTransitionException.class, () -> intake.attachWorkspace("W-x"));
    }

    // ==== T6: startImplement ====

    @Test
    public void t6_startImplement_requires_workspace_attached() {
        Requirement withWorkspace = approvedRequirement();
        withWorkspace.attachWorkspace("W-1");
        withWorkspace.startImplement(OWNER);
        assertEquals(RequirementStatus.IMPLEMENTING, withWorkspace.getStatus());

        Requirement withoutWorkspace = approvedRequirement();
        assertThrows(IllegalStateException.class, () -> withoutWorkspace.startImplement(OWNER));
    }

    // ==== T7: startVerify ====

    @Test
    public void t7_startVerify_from_implementing_should_enter_verifying() {
        Requirement requirement = implementingRequirement();

        requirement.startVerify(OWNER);

        assertEquals(RequirementStatus.VERIFYING, requirement.getStatus());
    }

    // ==== T8/T9: applyVerificationOutcome ====

    @Test
    public void t8_verified_outcome_should_enter_review() {
        Requirement requirement = verifyingRequirement();
        requirement.pullEvents();

        requirement.applyVerificationOutcome(VerificationOutcome.VERIFIED, "system:verify");

        assertEquals(RequirementStatus.REVIEW, requirement.getStatus());
        assertEquals("VERIFIED", requirement.pullEvents().get(0).getDetail());
    }

    @ParameterizedTest
    @EnumSource(value = VerificationOutcome.class, names = {"BLOCKED", "DEPLOY_FAILED"})
    public void t9_blocked_outcome_should_suspend_and_resume_back_to_implementing(VerificationOutcome outcome) {
        Requirement requirement = verifyingRequirement();

        requirement.applyVerificationOutcome(outcome, "system:verify");

        assertEquals(RequirementStatus.SUSPENDED, requirement.getStatus());
        // T9 评审修正: statusBeforeSuspend 固定记 IMPLEMENTING, 人工接管后 resume 回实现态先修再重验
        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatusBeforeSuspend());

        requirement.resume(OWNER);
        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
    }

    // ==== T10: markDelivered ====

    @Test
    public void t10_markDelivered_from_review_should_be_terminal() {
        Requirement requirement = reviewRequirement();

        requirement.markDelivered("system:webhook", "https://gitlab/mr/1");

        assertEquals(RequirementStatus.DELIVERED, requirement.getStatus());
    }

    // ==== T11: requestChanges ====

    @Test
    public void t11_requestChanges_should_return_to_implementing() {
        Requirement requirement = reviewRequirement();

        requirement.requestChanges(OWNER, "补充单测");

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
    }

    // ==== T12/T13: suspend / resume ====

    @Test
    public void t12_suspend_should_record_status_before_suspend() {
        Requirement requirement = plannedRequirement();

        requirement.suspend(OWNER, "等外部依赖");

        assertEquals(RequirementStatus.SUSPENDED, requirement.getStatus());
        assertEquals(RequirementStatus.PLANNED, requirement.getStatusBeforeSuspend());
    }

    @Test
    public void t12_suspend_requires_reason() {
        Requirement requirement = plannedRequirement();

        assertThrows(IllegalArgumentException.class, () -> requirement.suspend(OWNER, null));
    }

    @Test
    public void t13_resume_should_restore_status_before_suspend_and_clear_it() {
        Requirement requirement = plannedRequirement();
        requirement.suspend(OWNER, "等外部依赖");

        requirement.resume(OWNER);

        assertEquals(RequirementStatus.PLANNED, requirement.getStatus());
        assertNull(requirement.getStatusBeforeSuspend());
    }

    @Test
    public void t13_resume_without_status_before_suspend_should_reject() {
        Requirement requirement = rebuild(RequirementStatus.SUSPENDED, plan("p"), null, null);

        assertThrows(IllegalStateException.class, () -> requirement.resume(OWNER));
    }

    @Test
    public void suspend_on_suspended_should_reject_to_protect_status_before_suspend() {
        Requirement requirement = plannedRequirement();
        requirement.suspend(OWNER, "第一次");

        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.suspend(OWNER, "第二次"));
        assertEquals(RequirementStatus.PLANNED, requirement.getStatusBeforeSuspend());
    }

    // ==== T14: archive ====

    @Test
    public void t14_archive_allowed_from_any_non_terminal() {
        Requirement fromIntake = newIntakeRequirement();
        fromIntake.archive(OWNER, "不做了");
        assertEquals(RequirementStatus.ARCHIVED, fromIntake.getStatus());

        Requirement fromSuspended = plannedRequirement();
        fromSuspended.suspend(OWNER, "先挂起");
        fromSuspended.archive(OWNER, null);
        assertEquals(RequirementStatus.ARCHIVED, fromSuspended.getStatus());
    }

    // ==== T15: 终态拒绝一切 ====

    @ParameterizedTest
    @EnumSource(value = RequirementStatus.class, names = {"DELIVERED", "ARCHIVED"})
    public void t15_terminal_status_should_reject_every_action(RequirementStatus terminal) {
        Requirement requirement = rebuild(terminal, plan("p"), "W-1", null);

        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.attachPlan(plan("x"), OWNER));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.rejectPlan(OWNER, "r"));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.approve(OWNER));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.attachWorkspace("W-2"));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.startImplement(OWNER));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.startVerify(OWNER));
        assertThrows(IllegalRequirementTransitionException.class,
                () -> requirement.applyVerificationOutcome(VerificationOutcome.VERIFIED, OWNER));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.markDelivered(OWNER, null));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.requestChanges(OWNER, "r"));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.suspend(OWNER, "r"));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.resume(OWNER));
        assertThrows(IllegalRequirementTransitionException.class, () -> requirement.archive(OWNER, "r"));
    }

    // ==== 迁移表完整性: 穷举 状态×动作 矩阵, 与 §1.2 的合法集逐一比对 ====

    @Test
    public void transitions_table_should_match_design_matrix_exhaustively() {
        Map<RequirementStatus, Set<RequirementAction>> expected = new EnumMap<>(RequirementStatus.class);
        expected.put(RequirementStatus.INTAKE, EnumSet.of(
                RequirementAction.ATTACH_PLAN, RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.PLANNED, EnumSet.of(
                RequirementAction.ATTACH_PLAN, RequirementAction.REJECT_PLAN, RequirementAction.APPROVE,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.APPROVED, EnumSet.of(
                RequirementAction.ATTACH_WORKSPACE, RequirementAction.START_IMPLEMENT,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        // START_FIX: M2 遗留补齐——REVIEW 退回/熔断 resume 后在实现态发修复 run(不迁移只审计)
        expected.put(RequirementStatus.IMPLEMENTING, EnumSet.of(
                RequirementAction.START_FIX, RequirementAction.START_VERIFY,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.VERIFYING, EnumSet.of(
                RequirementAction.APPLY_VERIFICATION_OUTCOME, RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.REVIEW, EnumSet.of(
                RequirementAction.MARK_DELIVERED, RequirementAction.REQUEST_CHANGES,
                RequirementAction.SUSPEND, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.SUSPENDED, EnumSet.of(
                RequirementAction.RESUME, RequirementAction.ARCHIVE));
        expected.put(RequirementStatus.DELIVERED, EnumSet.noneOf(RequirementAction.class));
        expected.put(RequirementStatus.ARCHIVED, EnumSet.noneOf(RequirementAction.class));

        for (RequirementStatus status : RequirementStatus.values()) {
            for (RequirementAction action : RequirementAction.values()) {
                boolean shouldAllow = expected.get(status).contains(action);
                assertEquals(shouldAllow, RequirementTransitions.isAllowed(status, action),
                        "matrix mismatch: " + status + " × " + action);
            }
        }
    }

    // ==== 构造期不变量 + 事件缓冲 ====

    @Test
    public void create_should_validate_and_record_created_event() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "  标题  ", "描述", OWNER);

        assertEquals("标题", requirement.getTitle());
        assertEquals(RequirementStatus.INTAKE, requirement.getStatus());
        assertNotNull(requirement.getId());
        assertTrue(requirement.getId().getValue().startsWith("R"));
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_CREATED, events.get(0).getEventType());
    }

    @Test
    public void create_should_reject_blank_title_or_owner_or_null_source() {
        assertThrows(IllegalArgumentException.class,
                () -> Requirement.create(RequirementSource.BOARD, " ", "d", OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> Requirement.create(RequirementSource.BOARD, "t", "d", " "));
        assertThrows(IllegalArgumentException.class,
                () -> Requirement.create(null, "t", "d", OWNER));
    }

    @Test
    public void pullEvents_should_drain_buffer() {
        Requirement requirement = newIntakeRequirement();
        requirement.attachPlan(plan("p"), OWNER);

        assertFalse(requirement.pullEvents().isEmpty());
        assertTrue(requirement.pullEvents().isEmpty());
    }

    @Test
    public void action_should_require_actor() {
        Requirement requirement = newIntakeRequirement();

        assertThrows(IllegalArgumentException.class, () -> requirement.attachPlan(plan("p"), " "));
        assertThrows(IllegalArgumentException.class, () -> requirement.archive(null, "r"));
    }

    // ==== fixtures ====

    private Requirement newIntakeRequirement() {
        return Requirement.create(RequirementSource.BOARD, "标题", "描述", OWNER);
    }

    private Requirement plannedRequirement() {
        Requirement requirement = newIntakeRequirement();
        requirement.attachPlan(plan("plan"), OWNER);
        return requirement;
    }

    private Requirement approvedRequirement() {
        Requirement requirement = plannedRequirement();
        requirement.approve(OWNER);
        return requirement;
    }

    private Requirement implementingRequirement() {
        Requirement requirement = approvedRequirement();
        requirement.attachWorkspace("W-1");
        requirement.startImplement(OWNER);
        return requirement;
    }

    private Requirement verifyingRequirement() {
        Requirement requirement = implementingRequirement();
        requirement.startVerify(OWNER);
        return requirement;
    }

    private Requirement reviewRequirement() {
        Requirement requirement = verifyingRequirement();
        requirement.applyVerificationOutcome(VerificationOutcome.VERIFIED, "system:verify");
        return requirement;
    }

    private Requirement rebuild(RequirementStatus status, AgentPlan agentPlan,
                                String workspaceId, RequirementStatus statusBeforeSuspend) {
        return new Requirement(new RequirementId("R2607040001"), RequirementSource.BOARD, null,
                "标题", "描述", status, statusBeforeSuspend, agentPlan, OWNER,
                new ArrayList<>(), workspaceId, Instant.now(), Instant.now());
    }

    private AgentPlan plan(String text) {
        return new AgentPlan(text, null, null, Instant.now());
    }
}
