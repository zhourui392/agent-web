package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.review.ReviewApplicationException;
import com.example.agentweb.app.workbench.review.ReviewCandidateAppService;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.workbench.dto.GenerateReviewCandidateRequest;
import com.example.agentweb.interfaces.workbench.dto.ReviewCandidateResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Review Candidate 非持久化生成的 Owner HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/phases/REVIEW_REFACTOR",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ReviewCandidateController {

    private final ReviewCandidateAppService service;
    private final CurrentUserProvider currentUserProvider;

    public ReviewCandidateController(
            ReviewCandidateAppService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @PostMapping(
            path = "/review-candidates",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewCandidateResponse generate(
            @PathVariable("workbenchId") String workbenchId,
            @Valid @RequestBody GenerateReviewCandidateRequest ignoredRequest) {
        return ReviewCandidateResponse.from(service.generate(
                currentOwner(), parseWorkbenchId(workbenchId)));
    }

    @ExceptionHandler(ReviewApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleApplicationFailure(
            ReviewApplicationException failure) {
        return error(HttpStatus.CONFLICT,
                "WORKBENCH_REVIEW_CANDIDATE_SOURCE_UNAVAILABLE",
                failure.getMessage());
    }

    @ExceptionHandler({
            WorkbenchReviewCandidateRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception ignoredFailure) {
        return error(HttpStatus.BAD_REQUEST,
                "WORKBENCH_REVIEW_CANDIDATE_REQUEST_INVALID",
                "workbench review candidate request is invalid");
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
            throw new WorkbenchReviewCandidateRequestException();
        }
    }

    private ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
