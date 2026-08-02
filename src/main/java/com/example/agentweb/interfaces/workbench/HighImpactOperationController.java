package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.operation.HighImpactOperationOwnerService;
import com.example.agentweb.app.workbench.operation.HighImpactOperationProposalService;
import com.example.agentweb.app.workbench.operation.HighImpactOperationProjection;
import com.example.agentweb.app.workbench.operation.OperationApplicationErrorCode;
import com.example.agentweb.app.workbench.operation.OperationApplicationException;
import com.example.agentweb.app.workbench.operation.ProposeHighImpactOperationCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.HighImpactOperationDecision;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.workbench.dto.CreateHighImpactOperationRequest;
import com.example.agentweb.interfaces.workbench.dto.HighImpactOperationDecisionRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 高影响操作的 Owner 查询与独立人工决策边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/operations",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class HighImpactOperationController {

    private final HighImpactOperationOwnerService service;
    private final HighImpactOperationProposalService proposalService;
    private final CurrentUserProvider currentUserProvider;

    public HighImpactOperationController(
            HighImpactOperationOwnerService service,
            HighImpactOperationProposalService proposalService,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.proposalService = proposalService;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<HighImpactOperationProjection> propose(
            @PathVariable("workbenchId") String workbenchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateHighImpactOperationRequest request) {
        WorkbenchId parsedWorkbenchId = parseWorkbenchId(workbenchId);
        ProposeHighImpactOperationCommand command;
        try {
            command = new ProposeHighImpactOperationCommand(
                    idempotencyKey, request.getSourceRunId(),
                    parsePhase(request.getPhase()),
                    request.getTarget().toApplicationTarget().toDomainTarget(),
                    request.getSafeSummary());
        } catch (IllegalArgumentException failure) {
            throw new WorkbenchOperationRequestException();
        }
        HighImpactOperationProjection created = proposalService.propose(
                currentOwner(), parsedWorkbenchId, command);
        return ResponseEntity.created(URI.create(
                        "/api/workbenches/" + parsedWorkbenchId.getValue()
                                + "/operations/" + created.getOperationId()))
                .body(created);
    }

    @GetMapping("/{operationId}")
    public HighImpactOperationProjection get(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("operationId") String operationId) {
        return service.find(
                currentOwner(), parseWorkbenchId(workbenchId),
                parseOperationId(operationId));
    }

    @GetMapping
    public List<HighImpactOperationProjection> list(
            @PathVariable("workbenchId") String workbenchId) {
        return service.list(currentOwner(), parseWorkbenchId(workbenchId));
    }

    @PostMapping(
            path = "/{operationId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public HighImpactOperationProjection decide(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("operationId") String operationId,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @Valid @RequestBody HighImpactOperationDecisionRequest request) {
        return service.decide(
                currentOwner(), parseWorkbenchId(workbenchId),
                parseOperationId(operationId),
                parseExpectedVersion(expectedVersion),
                parseDecision(request.getDecision()), request.getReason());
    }

    @ExceptionHandler(OperationApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleApplicationFailure(
            OperationApplicationException failure) {
        HttpStatus status;
        String code;
        if (failure.getCode()
                == OperationApplicationErrorCode.VERSION_CONFLICT) {
            status = HttpStatus.CONFLICT;
            code = "WORKBENCH_OPERATION_VERSION_CONFLICT";
        } else if (failure.getCode()
                == OperationApplicationErrorCode.OPERATION_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
            code = "WORKBENCH_OPERATION_NOT_FOUND";
        } else if (failure.getCode()
                == OperationApplicationErrorCode.SOURCE_RUN_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
            code = "WORKBENCH_OPERATION_SOURCE_RUN_NOT_FOUND";
        } else {
            status = HttpStatus.NOT_FOUND;
            code = "WORKBENCH_NOT_FOUND";
        }
        Map<String, Object> body = new HashMap<String, Object>(3);
        body.put("code", code);
        body.put("message", failure.getMessage());
        if (failure.getCurrent() != null) {
            body.put("current", failure.getCurrent());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler({
            WorkbenchOperationRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception ignoredFailure) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", "WORKBENCH_OPERATION_REQUEST_INVALID");
        body.put("message", "workbench high-impact operation request is invalid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private OwnerReference currentOwner() {
        return OwnerReference.of(
                currentUserProvider.currentUserId(),
                currentUserProvider.currentUserName());
    }

    private WorkbenchId parseWorkbenchId(String value) {
        try {
            return WorkbenchId.of(value);
        } catch (RuntimeException failure) {
            throw new WorkbenchOperationRequestException();
        }
    }

    private String parseOperationId(String value) {
        if (value == null || value.trim().isEmpty()
                || value.length() > 128) {
            throw new WorkbenchOperationRequestException();
        }
        return value;
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new WorkbenchOperationRequestException();
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 0L) {
                throw new WorkbenchOperationRequestException();
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new WorkbenchOperationRequestException();
        }
    }

    private HighImpactOperationDecision parseDecision(String value) {
        try {
            return HighImpactOperationDecision.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new WorkbenchOperationRequestException();
        }
    }

    private WorkbenchPhase parsePhase(String value) {
        try {
            return WorkbenchPhase.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new WorkbenchOperationRequestException();
        }
    }
}
