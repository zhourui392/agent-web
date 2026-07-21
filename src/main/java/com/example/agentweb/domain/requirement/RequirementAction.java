package com.example.agentweb.domain.requirement;

/**
 * 需求状态机动作（detailed-design §1.2 迁移表的动作维度）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public enum RequirementAction {

    ATTACH_PLAN,
    REJECT_PLAN,
    APPROVE,
    ATTACH_WORKSPACE,
    START_IMPLEMENT,
    START_FIX,
    START_VERIFY,
    APPLY_VERIFICATION_OUTCOME,
    MARK_DELIVERED,
    REQUEST_CHANGES,
    SUSPEND,
    RESUME,
    ARCHIVE
}
