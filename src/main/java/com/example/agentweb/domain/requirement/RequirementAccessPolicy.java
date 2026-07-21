package com.example.agentweb.domain.requirement;

/**
 * 需求授权规则收拢：owner 可批，参与者可看可追加。
 *
 * <p>"审批人 ≠ 计划 run 发起 agent" 的人审门靠入口收口而非 ID 比对——approve 只从
 * Controller(UserContext 人类入口) 进入，编排/webhook/run 回调无调用路径；
 * {@code plan.sourceRunId} 仅审计溯源。admin 通道待管理后台接入需求线时扩展。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class RequirementAccessPolicy {

    /**
     * 是否可审批（T4 守卫）：仅需求属主。
     *
     * @param actor       操作人 userId
     * @param requirement 目标需求
     * @return true 表示可审批
     */
    public boolean canApprove(String actor, Requirement requirement) {
        return isNotBlank(actor) && actor.equals(requirement.getOwner());
    }

    /**
     * 是否可操作（查看/追加材料）：属主或参与者。
     *
     * @param actor       操作人 userId
     * @param requirement 目标需求
     * @return true 表示可操作
     */
    public boolean canOperate(String actor, Requirement requirement) {
        if (!isNotBlank(actor)) {
            return false;
        }
        return actor.equals(requirement.getOwner()) || requirement.getParticipants().contains(actor);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
