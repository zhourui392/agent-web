package com.example.agentweb.domain.requirement;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * FIX run 状态机守卫：仅 IMPLEMENTING 可发（首实现 T6 后 / REVIEW 退回 T11 后 / 熔断 resume T13 后），
 * 发起不改状态、只落审计事件——修复 run 是实现态内的再执行，不是新迁移。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementFixRunTest {

    private static final String OWNER = "V33215020";

    @Test
    public void startFixRun_in_implementing_should_record_event_and_keep_status() {
        Requirement requirement = implementingRequirement();
        requirement.pullEvents();

        requirement.startFixRun(OWNER);

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
        List<RequirementEvent> events = requirement.pullEvents();
        assertEquals(1, events.size());
        assertEquals(RequirementEvent.TYPE_FIX_RUN_STARTED, events.get(0).getEventType());
        assertEquals(OWNER, events.get(0).getActor());
    }

    @Test
    public void startFixRun_after_review_request_changes_should_be_allowed() {
        // REVIEW 退回 [T11] 后重发 run 的正路:回到 IMPLEMENTING 走 FIX,不再撞 T6 守卫
        Requirement requirement = implementingRequirement();
        requirement.startVerify(OWNER);
        requirement.applyVerificationOutcome(
                com.example.agentweb.domain.verification.VerificationOutcome.VERIFIED, "system:verify");
        requirement.requestChanges(OWNER, "评审意见: 补幂等");

        requirement.startFixRun(OWNER);

        assertEquals(RequirementStatus.IMPLEMENTING, requirement.getStatus());
    }

    @Test
    public void startFixRun_outside_implementing_should_throw_illegal_transition() {
        Requirement approved = approvedRequirement();
        assertThrows(IllegalRequirementTransitionException.class, () -> approved.startFixRun(OWNER));

        Requirement inReview = implementingRequirement();
        inReview.startVerify(OWNER);
        inReview.applyVerificationOutcome(
                com.example.agentweb.domain.verification.VerificationOutcome.VERIFIED, "system:verify");
        assertThrows(IllegalRequirementTransitionException.class, () -> inReview.startFixRun(OWNER));
    }

    @Test
    public void startFixRun_blank_actor_should_be_rejected() {
        Requirement requirement = implementingRequirement();

        assertThrows(IllegalArgumentException.class, () -> requirement.startFixRun(" "));
    }

    private Requirement approvedRequirement() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "标题", "描述", OWNER);
        requirement.attachPlan(new AgentPlan("计划", null, null, java.time.Instant.now()), OWNER);
        requirement.approve(OWNER);
        return requirement;
    }

    private Requirement implementingRequirement() {
        Requirement requirement = approvedRequirement();
        requirement.attachWorkspace("W1");
        requirement.startImplement(OWNER);
        return requirement;
    }
}
