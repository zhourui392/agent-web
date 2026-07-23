package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.HarnessExecutionResult;
import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.HarnessRuntimeExecutionNotFoundException;
import com.example.agentweb.app.harness.InvalidHarnessIdempotencyKeyException;
import com.example.agentweb.app.harness.RuntimeExecutionQueryService;
import com.example.agentweb.app.harness.RuntimeExecutionView;
import com.example.agentweb.app.harness.StartHarnessExecutionCommand;
import com.example.agentweb.domain.harness.HarnessStage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Locale;

/**
 * RuntimeExecution 的启动与脱敏查询 API 边界。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@RestController
@RequestMapping(path = "/api/harness/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessExecutionController {

    private static final int MAXIMUM_IDEMPOTENCY_KEY_LENGTH = 128;

    private final HarnessExecutionService executionService;
    private final RuntimeExecutionQueryService queryService;

    public HarnessExecutionController(HarnessExecutionService executionService,
                                      RuntimeExecutionQueryService queryService) {
        this.executionService = executionService;
        this.queryService = queryService;
    }

    @PostMapping("/{runId}/stages/{stage}/executions")
    public ResponseEntity<HarnessExecutionResult> start(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        HarnessStage harnessStage = stage(stage);
        HarnessExecutionResult result = executionService.start(new StartHarnessExecutionCommand(
                runId, harnessStage, requireIdempotencyKey(idempotencyKey)));
        String location = "/api/harness/runs/" + result.getRunId() + "/stages/"
                + result.getStage().name()
                + "/attempts/" + result.getAttemptNumber() + "/execution";
        return ResponseEntity.accepted().location(URI.create(location)).body(result);
    }

    @GetMapping("/{runId}/stages/{stage}/attempts/{attemptNumber}/execution")
    public RuntimeExecutionView find(@PathVariable("runId") String runId,
                                     @PathVariable("stage") String stage,
                                     @PathVariable("attemptNumber") int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attempt number must be positive");
        }
        HarnessStage harnessStage = stage(stage);
        return queryService.find(runId, harnessStage, attemptNumber)
                .orElseThrow(() -> new HarnessRuntimeExecutionNotFoundException(
                        runId, harnessStage.name(), attemptNumber));
    }

    private HarnessStage stage(String value) {
        return HarnessStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty()
                || value.trim().length() > MAXIMUM_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidHarnessIdempotencyKeyException();
        }
        return value.trim();
    }
}
