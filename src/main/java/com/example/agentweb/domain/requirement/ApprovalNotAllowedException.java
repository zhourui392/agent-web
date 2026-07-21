package com.example.agentweb.domain.requirement;

/**
 * 审批权限不足（T4 守卫：仅 owner 可批）。映射为 409 + APPROVAL_FORBIDDEN。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class ApprovalNotAllowedException extends RuntimeException {

    public ApprovalNotAllowedException(String actor) {
        super("approve rejected: actor not allowed, actor=" + actor);
    }
}
