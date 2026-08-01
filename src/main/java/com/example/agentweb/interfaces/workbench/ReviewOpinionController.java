package com.example.agentweb.interfaces.workbench;

import com.example.agentweb.app.workbench.review.ConfirmReviewModificationCommand;
import com.example.agentweb.app.workbench.review.ReviewApplicationErrorCode;
import com.example.agentweb.app.workbench.review.ReviewApplicationException;
import com.example.agentweb.app.workbench.review.ReviewConfirmationView;
import com.example.agentweb.app.workbench.review.ReviewOpinionView;
import com.example.agentweb.app.workbench.review.ReviewOwnerService;
import com.example.agentweb.app.workbench.review.SaveReviewOpinionCommand;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchId;
import com.example.agentweb.interfaces.workbench.dto.ConfirmReviewModificationRequest;
import com.example.agentweb.interfaces.workbench.dto.SaveReviewOpinionRequest;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Review Opinion 与 explicit MODIFY Confirmation 的 Owner HTTP 边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@RestController
@RequestMapping(
        path = "/api/workbenches/{workbenchId}/phases/REVIEW_REFACTOR",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ReviewOpinionController {

    private final ReviewOwnerService service;
    private final CurrentUserProvider currentUserProvider;

    public ReviewOpinionController(
            ReviewOwnerService service,
            CurrentUserProvider currentUserProvider) {
        this.service = service;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/review-opinion")
    public ReviewOpinionView getOpinion(
            @PathVariable("workbenchId") String workbenchId) {
        return service.getOpinion(
                currentOwner(), parseWorkbenchId(workbenchId));
    }

    @PutMapping(
            path = "/review-opinion",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReviewOpinionView saveOpinion(
            @PathVariable("workbenchId") String workbenchId,
            @RequestHeader(value = "If-Match", required = false)
                    String expectedVersion,
            @Valid @RequestBody SaveReviewOpinionRequest request) {
        return service.saveOpinion(
                currentOwner(), new SaveReviewOpinionCommand(
                        parseWorkbenchId(workbenchId),
                        parseExpectedVersion(expectedVersion),
                        request.getContent()));
    }

    @GetMapping("/review-confirmation")
    public ReviewConfirmationView getConfirmation(
            @PathVariable("workbenchId") String workbenchId) {
        return service.getConfirmation(
                currentOwner(), parseWorkbenchId(workbenchId));
    }

    @PostMapping(
            path = "/review-confirmation",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ReviewConfirmationView> confirmModification(
            @PathVariable("workbenchId") String workbenchId,
            @Valid @RequestBody ConfirmReviewModificationRequest request) {
        ReviewConfirmationView view = service.confirmModification(
                currentOwner(), new ConfirmReviewModificationCommand(
                        parseWorkbenchId(workbenchId),
                        request.getOpinionVersion().longValue(),
                        request.getOpinionHash()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @ExceptionHandler(ReviewApplicationException.class)
    public ResponseEntity<Map<String, Object>> handleApplicationFailure(
            ReviewApplicationException failure) {
        HttpStatus status = failure.getCode()
                == ReviewApplicationErrorCode.VERSION_CONFLICT
                ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        String code;
        if (failure.getCode()
                == ReviewApplicationErrorCode.OPINION_NOT_FOUND) {
            code = "WORKBENCH_REVIEW_OPINION_NOT_FOUND";
        } else if (failure.getCode()
                == ReviewApplicationErrorCode.CONFIRMATION_NOT_FOUND) {
            code = "WORKBENCH_REVIEW_CONFIRMATION_NOT_FOUND";
        } else {
            code = "WORKBENCH_REVIEW_VERSION_CONFLICT";
        }
        Map<String, Object> body = new HashMap<String, Object>(3);
        body.put("code", code);
        body.put("message", failure.getMessage());
        if (failure.getCurrentOpinion() != null) {
            body.put("current", failure.getCurrentOpinion());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler({
            WorkbenchReviewRequestException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(
            Exception ignoredFailure) {
        Map<String, Object> body = new HashMap<String, Object>(2);
        body.put("code", "WORKBENCH_REVIEW_REQUEST_INVALID");
        body.put("message", "workbench review request is invalid");
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
            throw new WorkbenchReviewRequestException();
        }
    }

    private long parseExpectedVersion(String value) {
        if (value == null) {
            throw new WorkbenchReviewRequestException();
        }
        try {
            long version = Long.parseLong(value.trim());
            if (version < 0L) {
                throw new WorkbenchReviewRequestException();
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new WorkbenchReviewRequestException();
        }
    }
}
