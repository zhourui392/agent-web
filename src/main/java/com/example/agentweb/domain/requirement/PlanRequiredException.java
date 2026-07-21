package com.example.agentweb.domain.requirement;

/**
 * approve 时计划为空（T4 守卫，防御性——PLANNED 态正常必有 plan）。映射为 409 + PLAN_EMPTY。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class PlanRequiredException extends RuntimeException {

    public PlanRequiredException(String requirementId) {
        super("approve rejected: plan required, requirement=" + requirementId);
    }
}
