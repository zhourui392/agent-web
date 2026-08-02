package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchReconciliationException;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Admin Workbench 稳定且不泄漏内部异常正文的 HTTP 错误合同。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestControllerAdvice(assignableTypes = AdminWorkbenchController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AdminWorkbenchExceptionHandler {

    @ExceptionHandler(AdminWorkbenchUnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> unauthorized() {
        return error(HttpStatus.UNAUTHORIZED,
                "WORKBENCH_ADMIN_UNAUTHORIZED",
                "administrator login is required");
    }

    @ExceptionHandler(AdminWorkbenchForbiddenException.class)
    public ResponseEntity<Map<String, Object>> forbidden() {
        return error(HttpStatus.FORBIDDEN,
                "WORKBENCH_ADMIN_FORBIDDEN",
                "administrator role is required");
    }

    @ExceptionHandler(AdminWorkbenchNotFoundException.class)
    public ResponseEntity<Map<String, Object>> workbenchNotFound() {
        return error(HttpStatus.NOT_FOUND, "WORKBENCH_NOT_FOUND",
                "workbench was not found");
    }

    @ExceptionHandler(AdminWorkbenchRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> runNotFound() {
        return error(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND",
                "workbench run was not found");
    }

    @ExceptionHandler({IllegalArgumentException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST,
                "WORKBENCH_ADMIN_REQUEST_INVALID",
                "admin workbench request is invalid");
    }

    @ExceptionHandler(AdminWorkbenchReconciliationException.class)
    public ResponseEntity<Map<String, Object>> reconciliationFailed() {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORKBENCH_ADMIN_RECONCILIATION_FAILED",
                "workbench run reconciliation failed");
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> internalFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "WORKBENCH_ADMIN_INTERNAL_ERROR",
                "admin workbench operation failed");
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
