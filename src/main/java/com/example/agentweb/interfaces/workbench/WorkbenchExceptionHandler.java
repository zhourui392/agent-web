package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.runtime.port.RuntimePreflightException;
import com.example.agentweb.app.workbench.WorkbenchNotFoundException;
import com.example.agentweb.app.workbench.WorkspaceFailureCode;
import com.example.agentweb.app.workbench.WorkspaceOperationException;
import com.example.agentweb.app.workbench.query.PhaseConversationMessageTooLargeException;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationErrorCode;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityApplicationException;
import com.example.agentweb.app.workbench.attachment.UploadedAttachmentStorageException;
import com.example.agentweb.app.workbench.document.DocumentFailureCode;
import com.example.agentweb.app.workbench.document.DocumentOperationException;
import com.example.agentweb.app.workbench.run.WorkbenchRunCursorExpiredException;
import com.example.agentweb.app.workbench.run.WorkbenchRunNotFoundException;
import com.example.agentweb.app.workbench.run.WorkbenchRunUnavailableException;
import com.example.agentweb.domain.capability.CapabilityCatalogException;
import com.example.agentweb.domain.capability.CapabilityResolutionException;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Workbench 领域与 Workspace 端口错误的稳定 HTTP 映射。
 *
 * @author alex
 * @since 2026-08-01
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = WorkbenchController.class)
public class WorkbenchExceptionHandler {

    private static final Map<WorkspaceFailureCode, HttpStatus> STATUS_BY_CODE =
            statusByCode();
    private static final Map<WorkbenchErrorCode, HttpStatus> WORKBENCH_STATUS_BY_CODE =
            workbenchStatusByCode();
    private static final Map<DocumentFailureCode, HttpStatus> DOCUMENT_STATUS_BY_CODE =
            documentStatusByCode();
    private static final Map<PhaseCapabilityApplicationErrorCode,
            CapabilityErrorContract> CAPABILITY_APPLICATION_ERROR_BY_CODE =
            capabilityApplicationErrorByCode();
    private static final Map<String, CapabilityErrorContract>
            CAPABILITY_RESOLUTION_ERROR_BY_CODE =
            capabilityResolutionErrorByCode();
    private static final CapabilityErrorContract PROFILE_UNAVAILABLE =
            new CapabilityErrorContract(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "WORKBENCH_PROFILE_UNAVAILABLE");

    @ExceptionHandler(WorkspaceOperationException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspaceOperation(
            WorkspaceOperationException exception) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", exception.getCode().name());
        body.put("message", exception.getMessage());
        return ResponseEntity.status(STATUS_BY_CODE.get(exception.getCode())).body(body);
    }

    @ExceptionHandler(WorkbenchNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkbenchNotFound(
            WorkbenchNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "WORKBENCH_NOT_FOUND",
                exception.getMessage());
    }

    @ExceptionHandler(WorkbenchRunNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleWorkbenchRunNotFound(
            WorkbenchRunNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "WORKBENCH_RUN_NOT_FOUND",
                exception.getMessage());
    }

    @ExceptionHandler(WorkbenchRunUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleWorkbenchRunUnavailable(
            WorkbenchRunUnavailableException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORKBENCH_RUN_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(RuntimePreflightException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimePreflight(
            RuntimePreflightException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORKBENCH_RUN_UNAVAILABLE",
                "workbench run service is unavailable");
    }

    @ExceptionHandler(WorkbenchRunCursorExpiredException.class)
    public ResponseEntity<Map<String, Object>> handleWorkbenchRunCursorExpired(
            WorkbenchRunCursorExpiredException exception) {
        Map<String, Object> body = new HashMap<String, Object>(5);
        body.put("code", "WORKBENCH_RUN_CURSOR_EXPIRED");
        body.put("message", exception.getMessage());
        body.put("runId", exception.getRunId());
        body.put("earliestRetainedSeq",
                exception.getEarliestRetainedSeq());
        body.put("lastEventSeq", exception.getLastEventSeq());
        return ResponseEntity.status(HttpStatus.GONE).body(body);
    }

    @ExceptionHandler(PhaseConversationMessageTooLargeException.class)
    public ResponseEntity<Map<String, Object>> handlePhaseMessageTooLarge(
            PhaseConversationMessageTooLargeException exception) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE,
                "WORKBENCH_PHASE_MESSAGE_TOO_LARGE",
                exception.getMessage());
    }

    @ExceptionHandler(WorkbenchDomainException.class)
    public ResponseEntity<Map<String, Object>> handleWorkbenchDomain(
            WorkbenchDomainException exception) {
        return error(WORKBENCH_STATUS_BY_CODE.get(exception.getCode()),
                "WORKBENCH_" + exception.getCode().name(), exception.getMessage());
    }

    @ExceptionHandler(UploadedAttachmentStorageException.class)
    public ResponseEntity<Map<String, Object>> handleUploadedAttachmentStorage(
            UploadedAttachmentStorageException exception) {
        return error(HttpStatus.SERVICE_UNAVAILABLE,
                "WORKBENCH_ATTACHMENT_STORAGE_UNAVAILABLE",
                "uploaded attachment storage is unavailable");
    }

    @ExceptionHandler(DocumentOperationException.class)
    public ResponseEntity<Map<String, Object>> handleDocumentOperation(
            DocumentOperationException exception) {
        return error(DOCUMENT_STATUS_BY_CODE.get(exception.getCode()),
                exception.getCode().name(), exception.getMessage());
    }

    @ExceptionHandler(PhaseCapabilityApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityApplication(
            PhaseCapabilityApplicationException exception) {
        CapabilityErrorContract contract =
                CAPABILITY_APPLICATION_ERROR_BY_CODE.get(exception.getCode());
        return error(contract.status, contract.code, exception.getMessage());
    }

    @ExceptionHandler(CapabilityResolutionException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityResolution(
            CapabilityResolutionException exception) {
        CapabilityErrorContract contract =
                CAPABILITY_RESOLUTION_ERROR_BY_CODE.getOrDefault(
                        exception.getCode(), PROFILE_UNAVAILABLE);
        return error(contract.status, contract.code, exception.getMessage());
    }

    @ExceptionHandler(CapabilityCatalogException.class)
    public ResponseEntity<Map<String, Object>> handleCapabilityCatalog(
            CapabilityCatalogException exception) {
        return error(PROFILE_UNAVAILABLE.status, PROFILE_UNAVAILABLE.code,
                "phase capability profile is unavailable");
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private static Map<WorkspaceFailureCode, HttpStatus> statusByCode() {
        EnumMap<WorkspaceFailureCode, HttpStatus> statuses =
                new EnumMap<WorkspaceFailureCode, HttpStatus>(WorkspaceFailureCode.class);
        statuses.put(WorkspaceFailureCode.WORKSPACE_SELECTION_INVALID,
                HttpStatus.BAD_REQUEST);
        statuses.put(WorkspaceFailureCode.WORKSPACE_PATH_FORBIDDEN,
                HttpStatus.FORBIDDEN);
        statuses.put(WorkspaceFailureCode.WORKSPACE_TOPOLOGY_CHANGED,
                HttpStatus.CONFLICT);
        statuses.put(WorkspaceFailureCode.WORKSPACE_CAPTURE_UNSTABLE,
                HttpStatus.CONFLICT);
        statuses.put(WorkspaceFailureCode.WORKSPACE_SELECTION_REQUIRED,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.WORKSPACE_REPOSITORY_NOT_FOUND,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.WORKSPACE_REPOSITORY_HEAD_MISSING,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.WORKSPACE_REPOSITORY_OVERLAP,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.WORKSPACE_GIT_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE);
        statuses.put(WorkspaceFailureCode.WORKSPACE_DISCOVERY_LIMIT_EXCEEDED,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.WORKSPACE_MANIFEST_INVALID,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkspaceFailureCode.REPOSITORY_SCOPE_VIOLATION,
                HttpStatus.FORBIDDEN);
        return Collections.unmodifiableMap(statuses);
    }

    private static Map<WorkbenchErrorCode, HttpStatus> workbenchStatusByCode() {
        EnumMap<WorkbenchErrorCode, HttpStatus> statuses =
                new EnumMap<WorkbenchErrorCode, HttpStatus>(WorkbenchErrorCode.class);
        statuses.put(WorkbenchErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST);
        statuses.put(WorkbenchErrorCode.OWNER_REQUIRED, HttpStatus.NOT_FOUND);
        statuses.put(WorkbenchErrorCode.ARCHIVED, HttpStatus.GONE);
        statuses.put(WorkbenchErrorCode.PHASE_RUN_ACTIVE, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.WRITE_RUN_ACTIVE, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.PHASE_RESTART_INVALID, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.PHASE_TRANSITION_INVALID, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.RUN_MODE_FORBIDDEN, HttpStatus.FORBIDDEN);
        statuses.put(WorkbenchErrorCode.CONVERSATION_CONFLICT, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.REPOSITORY_SCOPE_INVALID,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkbenchErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.RUN_BINDING_CORRUPTED,
                HttpStatus.INTERNAL_SERVER_ERROR);
        statuses.put(WorkbenchErrorCode.HANDOFF_SECRET_DETECTED,
                HttpStatus.UNPROCESSABLE_ENTITY);
        statuses.put(WorkbenchErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.ATTACHMENT_INVALID,
                HttpStatus.BAD_REQUEST);
        statuses.put(WorkbenchErrorCode.ATTACHMENT_TOO_LARGE,
                HttpStatus.PAYLOAD_TOO_LARGE);
        statuses.put(WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                HttpStatus.GONE);
        statuses.put(WorkbenchErrorCode.OPERATION_TRANSITION_INVALID,
                HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.OPERATION_TARGET_CHANGED,
                HttpStatus.CONFLICT);
        statuses.put(WorkbenchErrorCode.OPERATION_EXECUTION_UNAVAILABLE,
                HttpStatus.SERVICE_UNAVAILABLE);
        return Collections.unmodifiableMap(statuses);
    }

    private static Map<DocumentFailureCode, HttpStatus> documentStatusByCode() {
        EnumMap<DocumentFailureCode, HttpStatus> statuses =
                new EnumMap<DocumentFailureCode, HttpStatus>(DocumentFailureCode.class);
        statuses.put(DocumentFailureCode.WORKBENCH_DOCUMENT_REQUEST_INVALID,
                HttpStatus.BAD_REQUEST);
        statuses.put(DocumentFailureCode.WORKBENCH_DOCUMENT_NOT_FOUND,
                HttpStatus.NOT_FOUND);
        statuses.put(DocumentFailureCode.WORKBENCH_DOCUMENT_TOO_LARGE,
                HttpStatus.PAYLOAD_TOO_LARGE);
        statuses.put(DocumentFailureCode.WORKBENCH_DOCUMENT_UNSUPPORTED,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        statuses.put(DocumentFailureCode.WORKBENCH_DOCUMENT_CHANGED_DURING_READ,
                HttpStatus.CONFLICT);
        return Collections.unmodifiableMap(statuses);
    }

    private static Map<PhaseCapabilityApplicationErrorCode,
            CapabilityErrorContract> capabilityApplicationErrorByCode() {
        EnumMap<PhaseCapabilityApplicationErrorCode, CapabilityErrorContract>
                errors = new EnumMap<PhaseCapabilityApplicationErrorCode,
                CapabilityErrorContract>(
                PhaseCapabilityApplicationErrorCode.class);
        errors.put(PhaseCapabilityApplicationErrorCode.OVERRIDE_NOT_FOUND,
                new CapabilityErrorContract(
                        HttpStatus.NOT_FOUND,
                        "WORKBENCH_CAPABILITY_OVERRIDE_NOT_FOUND"));
        errors.put(PhaseCapabilityApplicationErrorCode.OVERRIDE_ALREADY_EXISTS,
                new CapabilityErrorContract(
                        HttpStatus.CONFLICT,
                        "WORKBENCH_CAPABILITY_OVERRIDE_ALREADY_EXISTS"));
        errors.put(PhaseCapabilityApplicationErrorCode.VERSION_CONFLICT,
                new CapabilityErrorContract(
                        HttpStatus.CONFLICT,
                        "WORKBENCH_CAPABILITY_VERSION_CONFLICT"));
        errors.put(PhaseCapabilityApplicationErrorCode.ESCALATION_DENIED,
                new CapabilityErrorContract(
                        HttpStatus.FORBIDDEN,
                        "WORKBENCH_CAPABILITY_ESCALATION_DENIED"));
        return Collections.unmodifiableMap(errors);
    }

    private static Map<String, CapabilityErrorContract>
            capabilityResolutionErrorByCode() {
        Map<String, CapabilityErrorContract> errors =
                new HashMap<String, CapabilityErrorContract>();
        errors.put("WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE",
                new CapabilityErrorContract(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "WORKBENCH_CAPABILITY_REQUIRED_UNAVAILABLE"));
        errors.put("WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE",
                new CapabilityErrorContract(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "WORKBENCH_RUNTIME_CAPABILITY_INCOMPATIBLE"));
        return Collections.unmodifiableMap(errors);
    }

    private static final class CapabilityErrorContract {
        private final HttpStatus status;
        private final String code;

        private CapabilityErrorContract(HttpStatus status, String code) {
            this.status = status;
            this.code = code;
        }
    }
}
