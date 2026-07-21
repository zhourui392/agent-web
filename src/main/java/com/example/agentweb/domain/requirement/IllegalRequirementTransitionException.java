package com.example.agentweb.domain.requirement;

import lombok.Getter;

/**
 * 非法状态迁移（T15 及迁移表外组合）。Controller 侧映射为 409 + ILLEGAL_TRANSITION。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public class IllegalRequirementTransitionException extends RuntimeException {

    private final RequirementStatus fromStatus;
    private final RequirementAction action;

    public IllegalRequirementTransitionException(RequirementStatus fromStatus, RequirementAction action) {
        super(action + " rejected: status=" + fromStatus);
        this.fromStatus = fromStatus;
        this.action = action;
    }
}
