package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.CreateHarnessRunCommand;
import com.example.agentweb.app.harness.HarnessAppService;
import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.HarnessMutationResult;
import com.example.agentweb.app.harness.HarnessRunQueryService;
import com.example.agentweb.app.harness.HarnessRunView;
import com.example.agentweb.app.harness.InvalidHarnessIdempotencyKeyException;
import com.example.agentweb.app.harness.RegisterHarnessArtifactCommand;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.interfaces.dto.HarnessApprovalRequest;
import com.example.agentweb.interfaces.dto.HarnessArtifactRequest;
import com.example.agentweb.interfaces.dto.HarnessCancelRequest;
import com.example.agentweb.interfaces.dto.HarnessGateRequest;
import com.example.agentweb.interfaces.dto.HarnessRunCreateRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Harness M1 管理 API 边界，负责 DTO、枚举、Header 和 HTTP 状态转换。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@RestController
@RequestMapping(path = "/api/harness/runs", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessController {

    private static final String DEFAULT_DEFINITION_VERSION = "harness@1.0.0";

    private final HarnessAppService appService;
    private final HarnessRunQueryService queryService;
    private final HarnessExecutionService executionService;

    public HarnessController(HarnessAppService appService, HarnessRunQueryService queryService,
                             HarnessExecutionService executionService) {
        this.appService = appService;
        this.queryService = queryService;
        this.executionService = executionService;
    }

    @PostMapping
    public ResponseEntity<HarnessMutationResult> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessRunCreateRequest request) {
        String key = requireIdempotencyKey(idempotencyKey);
        String definitionVersion = request.getDefinitionVersion() == null
                || request.getDefinitionVersion().trim().isEmpty()
                ? DEFAULT_DEFINITION_VERSION : request.getDefinitionVersion().trim();
        HarnessMutationResult result = appService.create(new CreateHarnessRunCommand(
                request.getTitle(), request.getWorkingDir(), request.getAgentType(),
                request.getEnvironment(), definitionVersion, key));
        return ResponseEntity.created(URI.create("/api/harness/runs/" + result.getRunId()))
                .body(result);
    }

    @GetMapping("/{runId}")
    public HarnessRunView find(@PathVariable("runId") String runId) {
        return queryService.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }

    @PostMapping("/{runId}/stages/{stage}/start")
    public ResponseEntity<HarnessMutationResult> start(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(appService.startStage(runId, stage(stage),
                requireIdempotencyKey(idempotencyKey)));
    }

    @PostMapping("/{runId}/stages/{stage}/artifacts")
    public ResponseEntity<HarnessMutationResult> artifact(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessArtifactRequest request) {
        RegisterHarnessArtifactCommand command = new RegisterHarnessArtifactCommand(
                runId, stage(stage), ArtifactType.valueOf(request.getArtifactType().trim().toUpperCase()),
                request.getContent().getBytes(StandardCharsets.UTF_8), request.getContentType(),
                ArtifactClassification.valueOf(request.getClassification().trim().toUpperCase()),
                Collections.<ArtifactReference>emptyList());
        return ResponseEntity.status(HttpStatus.CREATED).body(appService.registerArtifact(command));
    }

    @PostMapping("/{runId}/stages/{stage}/gates")
    public ResponseEntity<HarnessMutationResult> gate(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessGateRequest request) {
        List<String> evidence = request.getEvidenceReferences() == null
                ? Collections.<String>emptyList() : request.getEvidenceReferences();
        return accepted(appService.recordGate(runId, stage(stage), request.getRule(),
                request.getPassed().booleanValue(), evidence, request.getReason()));
    }

    @PostMapping("/{runId}/stages/{stage}/request-approval")
    public ResponseEntity<HarnessMutationResult> requestApproval(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage) {
        return accepted(appService.requestApproval(runId, stage(stage)));
    }

    @PostMapping("/{runId}/stages/{stage}/approve")
    public ResponseEntity<HarnessMutationResult> approve(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessApprovalRequest request) {
        return accepted(appService.approve(runId, stage(stage),
                request.getArtifactBaselineHash(), request.getReason()));
    }

    @PostMapping("/{runId}/stages/{stage}/reject")
    public ResponseEntity<HarnessMutationResult> reject(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessApprovalRequest request) {
        return accepted(appService.reject(runId, stage(stage),
                request.getArtifactBaselineHash(), request.getReason()));
    }

    @PostMapping("/{runId}/stages/{stage}/retry")
    public ResponseEntity<HarnessMutationResult> retry(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return accepted(appService.retryStage(runId, stage(stage),
                requireIdempotencyKey(idempotencyKey)));
    }

    @PostMapping("/{runId}/cancel")
    public ResponseEntity<HarnessMutationResult> cancel(
            @PathVariable("runId") String runId,
            @Valid @RequestBody HarnessCancelRequest request) {
        return accepted(executionService.cancel(runId, request.getReason()));
    }

    private ResponseEntity<HarnessMutationResult> accepted(HarnessMutationResult result) {
        return ResponseEntity.accepted().body(result);
    }

    private HarnessStage stage(String value) {
        return HarnessStage.valueOf(value.trim().toUpperCase());
    }

    private String requireIdempotencyKey(String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 128) {
            throw new InvalidHarnessIdempotencyKeyException();
        }
        return value.trim();
    }
}
