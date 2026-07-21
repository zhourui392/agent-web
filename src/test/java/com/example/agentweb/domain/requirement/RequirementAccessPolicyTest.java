package com.example.agentweb.domain.requirement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权策略测试：owner 可批，参与者可看可追加但不可批。
 * "审批人≠计划 run 发起 agent" 靠入口收口（approve 无编排内部调用路径），不做 ID 比对。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementAccessPolicyTest {

    private final RequirementAccessPolicy policy = new RequirementAccessPolicy();

    @Test
    public void owner_can_approve() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V33215020");

        assertTrue(policy.canApprove("V33215020", requirement));
    }

    @Test
    public void participant_can_operate_but_not_approve() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V33215020");
        requirement.addParticipant("V88888888");

        assertTrue(policy.canOperate("V88888888", requirement));
        assertFalse(policy.canApprove("V88888888", requirement));
    }

    @Test
    public void stranger_can_neither_operate_nor_approve() {
        Requirement requirement = Requirement.create(RequirementSource.BOARD, "t", "d", "V33215020");

        assertFalse(policy.canOperate("V77777777", requirement));
        assertFalse(policy.canApprove("V77777777", requirement));
        assertFalse(policy.canApprove(null, requirement));
        assertFalse(policy.canApprove(" ", requirement));
    }
}
