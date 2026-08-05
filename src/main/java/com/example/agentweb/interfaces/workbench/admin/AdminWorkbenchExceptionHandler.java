package com.example.agentweb.interfaces.workbench.admin;

import com.example.agentweb.app.workbench.admin.AdminWorkbenchReconciliationException;
import com.example.agentweb.app.workbench.admin.AdminWorkbenchRunNotFoundException;
import com.example.agentweb.domain.capability.CapabilityArtifactIntegrityException;
import com.example.agentweb.domain.capability.CapabilitySourceVersionConflictException;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.stage.StageCatalogException;
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
@RestControllerAdvice(basePackages = "com.example.agentweb.interfaces.workbench.admin")
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

    @ExceptionHandler(CapabilitySourceVersionConflictException.class)
    public ResponseEntity<Map<String, Object>> capabilitySourceVersionConflict(
            CapabilitySourceVersionConflictException failure) {
        return error(HttpStatus.CONFLICT, failure.getCode(),
                "capability source configuration was changed");
    }

    @ExceptionHandler(StageCatalogException.class)
    public ResponseEntity<Map<String, Object>> stageCatalog(
            StageCatalogException failure) {
        return error(stageCatalogStatus(failure.getCode()), failure.getCode(),
                "workbench Stage Catalog operation failed");
    }

    @ExceptionHandler(CapabilityArtifactIntegrityException.class)
    public ResponseEntity<Map<String, Object>> capabilityArtifactIntegrity(
            CapabilityArtifactIntegrityException failure) {
        HttpStatus status = "WORKBENCH_CAPABILITY_ARTIFACT_CONTENT_CONFLICT"
                .equals(failure.getCode())
                ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE;
        return error(status, failure.getCode(),
                "workbench capability artifact validation failed");
    }

    @ExceptionHandler(WorkbenchDomainException.class)
    public ResponseEntity<Map<String, Object>> workbenchDomain(
            WorkbenchDomainException failure) {
        if (failure.getCode() == WorkbenchErrorCode.VERSION_CONFLICT) {
            return error(HttpStatus.CONFLICT,
                    "WORKBENCH_CAPABILITY_VERSION_CONFLICT",
                    failure.getMessage());
        }
        return error(HttpStatus.BAD_REQUEST,
                failure.getCode().name(),
                failure.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> internalFailure() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR,
                "WORKBENCH_ADMIN_INTERNAL_ERROR",
                "admin workbench operation failed");
    }

    private HttpStatus stageCatalogStatus(String code) {
        if ("WORKBENCH_STAGE_DEFINITION_NOT_FOUND".equals(code)) {
            return HttpStatus.NOT_FOUND;
        }
        if ("WORKBENCH_STAGE_CATALOG_VERSION_CONFLICT".equals(code)
                || "WORKBENCH_STAGE_DEFINITION_VERSION_CONFLICT".equals(code)
                || "WORKBENCH_STAGE_SEQUENCE_CONFLICT".equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if ("WORKBENCH_STAGE_CAPABILITY_UNAVAILABLE".equals(code)
                || "WORKBENCH_STAGE_RUNTIME_INCOMPATIBLE".equals(code)) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        return HttpStatus.BAD_REQUEST;
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
