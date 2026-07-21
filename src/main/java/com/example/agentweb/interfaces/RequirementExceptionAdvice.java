package com.example.agentweb.interfaces;

import com.example.agentweb.app.delivery.CredentialInsufficientException;
import com.example.agentweb.domain.delivery.PushRefForbiddenException;
import com.example.agentweb.domain.requirement.ApprovalNotAllowedException;
import com.example.agentweb.domain.requirement.IllegalRequirementTransitionException;
import com.example.agentweb.domain.requirement.OwnerUnresolvedException;
import com.example.agentweb.domain.requirement.PlanRequiredException;
import com.example.agentweb.domain.requirement.RequirementNotFoundException;
import com.example.agentweb.domain.requirement.RequirementQuotaExceededException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * 需求线域异常 → 统一错误响应 {@code {code, message, retryable}} 映射。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
@RestControllerAdvice
@ConditionalOnProperty(name = "agent.requirement.enabled", havingValue = "true")
public class RequirementExceptionAdvice {

    @ExceptionHandler(IllegalRequirementTransitionException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalTransition(
            IllegalRequirementTransitionException ex) {
        Map<String, Object> body = body(RequirementErrorCode.ILLEGAL_TRANSITION, ex.getMessage());
        body.put("from", ex.getFromStatus().name());
        body.put("action", ex.getAction().name());
        return respond(RequirementErrorCode.ILLEGAL_TRANSITION, body);
    }

    @ExceptionHandler(RequirementQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(RequirementQuotaExceededException ex) {
        Map<String, Object> body = body(RequirementErrorCode.QUOTA_EXCEEDED, ex.getMessage());
        body.put("active", ex.getActiveCount());
        body.put("max", ex.getMaxActivePerUser());
        return respond(RequirementErrorCode.QUOTA_EXCEEDED, body);
    }

    @ExceptionHandler(PlanRequiredException.class)
    public ResponseEntity<Map<String, Object>> handlePlanRequired(PlanRequiredException ex) {
        return respond(RequirementErrorCode.PLAN_EMPTY, body(RequirementErrorCode.PLAN_EMPTY, ex.getMessage()));
    }

    @ExceptionHandler(ApprovalNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleApprovalForbidden(ApprovalNotAllowedException ex) {
        return respond(RequirementErrorCode.APPROVAL_FORBIDDEN,
                body(RequirementErrorCode.APPROVAL_FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(RequirementNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(RequirementNotFoundException ex) {
        return respond(RequirementErrorCode.REQUIREMENT_NOT_FOUND,
                body(RequirementErrorCode.REQUIREMENT_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(CredentialInsufficientException.class)
    public ResponseEntity<Map<String, Object>> handleCredentialInsufficient(
            CredentialInsufficientException ex) {
        Map<String, Object> body = body(RequirementErrorCode.CREDENTIAL_INSUFFICIENT, ex.getMessage());
        // 前端按 guide 跳既有 git 设置入口（GitConfigController /api/user/git-config 的设置页）
        body.put("guide", "settings:git-config");
        return respond(RequirementErrorCode.CREDENTIAL_INSUFFICIENT, body);
    }

    @ExceptionHandler(PushRefForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handlePushRefForbidden(PushRefForbiddenException ex) {
        return respond(RequirementErrorCode.PUSH_REF_FORBIDDEN,
                body(RequirementErrorCode.PUSH_REF_FORBIDDEN, ex.getMessage()));
    }

    @ExceptionHandler(OwnerUnresolvedException.class)
    public ResponseEntity<Map<String, Object>> handleOwnerUnresolved(OwnerUnresolvedException ex) {
        return respond(RequirementErrorCode.OWNER_UNRESOLVED,
                body(RequirementErrorCode.OWNER_UNRESOLVED, ex.getMessage()));
    }

    @ExceptionHandler(com.example.agentweb.domain.verification.VerificationHaltedException.class)
    public ResponseEntity<Map<String, Object>> handleVerificationHalted(
            com.example.agentweb.domain.verification.VerificationHaltedException ex) {
        return respond(RequirementErrorCode.VERIFICATION_HALTED,
                body(RequirementErrorCode.VERIFICATION_HALTED, ex.getMessage()));
    }

    private Map<String, Object> body(RequirementErrorCode code, String message) {
        Map<String, Object> body = new HashMap<>(16);
        body.put("code", code.name());
        body.put("message", message);
        body.put("retryable", code.isRetryable());
        return body;
    }

    private ResponseEntity<Map<String, Object>> respond(RequirementErrorCode code, Map<String, Object> body) {
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }
}
