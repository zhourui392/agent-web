package com.example.agentweb.interfaces;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 需求线错误码（HTTP 词汇留在 interfaces，域只抛语义异常，Advice 做映射）。
 * 前端按 code 查文案与动作指引，message 仅兜底。M0 立机制，后续里程碑只扩码表。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@Getter
public enum RequirementErrorCode {

    ILLEGAL_TRANSITION(HttpStatus.CONFLICT, false),
    QUOTA_EXCEEDED(HttpStatus.CONFLICT, true),
    PLAN_EMPTY(HttpStatus.CONFLICT, false),
    APPROVAL_FORBIDDEN(HttpStatus.CONFLICT, false),
    REQUIREMENT_NOT_FOUND(HttpStatus.NOT_FOUND, false),
    CREDENTIAL_INSUFFICIENT(HttpStatus.CONFLICT, true),
    PUSH_REF_FORBIDDEN(HttpStatus.CONFLICT, false),
    WORKSPACE_MISSING(HttpStatus.CONFLICT, false),
    OWNER_UNRESOLVED(HttpStatus.UNPROCESSABLE_ENTITY, false),
    VERIFICATION_HALTED(HttpStatus.CONFLICT, false);

    private final HttpStatus httpStatus;
    private final boolean retryable;

    RequirementErrorCode(HttpStatus httpStatus, boolean retryable) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
    }
}
