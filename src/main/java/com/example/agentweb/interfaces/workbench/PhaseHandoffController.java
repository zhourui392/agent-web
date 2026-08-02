package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.handoff.AcceptHandoffReceptionCommand;
import com.example.agentweb.app.workbench.handoff.HandoffApplicationErrorCode;
import com.example.agentweb.app.workbench.handoff.HandoffApplicationException;
import com.example.agentweb.app.workbench.handoff.HandoffReceptionProjection;
import com.example.agentweb.app.workbench.handoff.HandoffSourcePreview;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffCandidateAppService;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffContentCommand;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffContentInput;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffOwnerService;
import com.example.agentweb.app.workbench.handoff.PhaseHandoffProjection;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import com.example.agentweb.interfaces.workbench.dto.AcceptHandoffReceptionRequest;
import com.example.agentweb.interfaces.workbench.dto.GeneratePhaseHandoffCandidateRequest;
import com.example.agentweb.interfaces.workbench.dto.PhaseHandoffCandidateResponse;
import com.example.agentweb.interfaces.workbench.dto.PhaseHandoffRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Workbench Phase Handoff 与 Reception 的 Owner HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/phases/{phase}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class PhaseHandoffController {

    private final PhaseHandoffOwnerService service;
    private final PhaseHandoffCandidateAppService candidateService;
    private final CurrentUserProvider currentUserProvider;

    public PhaseHandoffController(
            PhaseHandoffOwnerService service,
            PhaseHandoffCandidateAppService candidateService,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.candidateService = candidateService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/handoff")
    public PhaseHandoffProjection get(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase) {
        return service.get(
                currentOwner(), parseWorkbenchId(workbenchId),
                parsePhase(phase));
    }

    @PutMapping(
            path = "/handoff",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PhaseHandoffProjection put(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @Valid @RequestBody PhaseHandoffRequest request) {
        return service.save(
                currentOwner(), parseWorkbenchId(workbenchId),
                parsePhase(phase), parseExpectedVersion(expectedVersion),
                content(request));
    }

    @GetMapping("/handoff-source")
    public HandoffSourcePreview source(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String phase) {
        return service.source(
                currentOwner(), parseWorkbenchId(workbenchId),
                parsePhase(phase));
    }

    @PostMapping(
            path = "/handoff-receptions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public HandoffReceptionProjection accept(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String targetPhase,
            @Valid @RequestBody AcceptHandoffReceptionRequest request) {
        AcceptHandoffReceptionCommand command =
                new AcceptHandoffReceptionCommand(
                        parseWorkbenchId(workbenchId),
                        parsePhase(targetPhase),
                        parsePhase(request.getSourcePhase()),
                        request.getSourceVersion().longValue(),
                        request.getSourceHash());
        return service.accept(currentOwner(), command);
    }

    @PostMapping(
            path = "/handoff-candidates",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public PhaseHandoffCandidateResponse candidate(
            @PathVariable("workbenchId") String workbenchId,
            @PathVariable("phase") String sourcePhase,
            @Valid @RequestBody
                    GeneratePhaseHandoffCandidateRequest ignoredRequest) {
        return PhaseHandoffCandidateResponse.from(
                candidateService.generate(
                        currentOwner(), parseWorkbenchId(workbenchId),
                        parsePhase(sourcePhase)));
    }

    @ExceptionHandler(HandoffApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleApplicationFailure(
            HandoffApplicationException failure) {
        HttpStatus status;
        String code;
        if (failure.getCode()
                == HandoffApplicationErrorCode.WORKBENCH_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
            code = "WORKBENCH_NOT_FOUND";
        } else if (failure.getCode()
                == HandoffApplicationErrorCode.HANDOFF_NOT_FOUND) {
            status = HttpStatus.NOT_FOUND;
            code = "WORKBENCH_HANDOFF_NOT_FOUND";
        } else if (failure.getCode()
                == HandoffApplicationErrorCode.VERSION_CONFLICT) {
            status = HttpStatus.CONFLICT;
            code = "WORKBENCH_HANDOFF_VERSION_CONFLICT";
        } else if (failure.getCode()
                == HandoffApplicationErrorCode.RUN_REFERENCE_INVALID) {
            status = HttpStatus.UNPROCESSABLE_ENTITY;
            code = "WORKBENCH_RUN_REFERENCE_INVALID";
        } else if (failure.getCode()
                == HandoffApplicationErrorCode.CANDIDATE_SOURCE_UNAVAILABLE) {
            status = HttpStatus.CONFLICT;
            code = "WORKBENCH_HANDOFF_CANDIDATE_SOURCE_UNAVAILABLE";
        } else {
            status = HttpStatus.CONFLICT;
            code = "WORKBENCH_HANDOFF_SOURCE_CHANGED";
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
            WorkbenchHandoffRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception ignoredFailure) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", "WORKBENCH_HANDOFF_REQUEST_INVALID");
        body.put("message", "workbench handoff request is invalid");
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
            throw new WorkbenchHandoffRequestException();
        }
    }

    private WorkbenchPhase parsePhase(String value) {
        try {
            return WorkbenchPhase.valueOf(
                    value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new WorkbenchHandoffRequestException();
        }
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new WorkbenchHandoffRequestException();
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 0L) {
                throw new WorkbenchHandoffRequestException();
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new WorkbenchHandoffRequestException();
        }
    }

    private PhaseHandoffContentCommand content(
            PhaseHandoffRequest request) {
        List<PhaseHandoffContentInput.DecisionInput> decisions =
                new ArrayList<PhaseHandoffContentInput.DecisionInput>();
        for (PhaseHandoffRequest.DecisionRequest value
                : request.getDecisions()) {
            decisions.add(new PhaseHandoffContentInput.DecisionInput(
                    value.getText(), value.getRationale()));
        }
        List<PhaseHandoffContentInput.OpenQuestionInput> questions =
                new ArrayList<PhaseHandoffContentInput.OpenQuestionInput>();
        for (PhaseHandoffRequest.OpenQuestionRequest value
                : request.getOpenQuestions()) {
            questions.add(new PhaseHandoffContentInput.OpenQuestionInput(
                    value.getText(), value.getOwnerHint()));
        }
        List<PhaseHandoffContentInput.DocumentInput> files =
                new ArrayList<PhaseHandoffContentInput.DocumentInput>();
        for (PhaseHandoffRequest.DocumentReferenceRequest value
                : request.getPinnedFiles()) {
            files.add(new PhaseHandoffContentInput.DocumentInput(
                    value.getRepositoryKey(), value.getRelativePath()));
        }
        List<String> runIds = new ArrayList<String>();
        for (PhaseHandoffRequest.RunReferenceRequest value
                : request.getReferencedRuns()) {
            runIds.add(value.getRunId());
        }
        return PhaseHandoffContentCommand.from(
                new PhaseHandoffContentInput(
                        request.getSummary(), decisions, questions,
                        files, runIds));
    }
}
