package com.example.agentweb.interfaces;

import com.example.agentweb.app.harness.CreateHarnessRunCommand;
import com.example.agentweb.app.harness.DeploymentExecutionQueryService;
import com.example.agentweb.app.harness.DeploymentExecutionView;
import com.example.agentweb.app.harness.HarnessDeploymentExecutionNotFoundException;
import com.example.agentweb.app.harness.HarnessDeploymentReadinessQueryService;
import com.example.agentweb.app.harness.HarnessDeploymentReadinessView;
import com.example.agentweb.app.harness.HarnessDeploymentResult;
import com.example.agentweb.app.harness.HarnessDeploymentService;
import com.example.agentweb.app.harness.HarnessArtifactContentView;
import com.example.agentweb.app.harness.HarnessArtifactNotFoundException;
import com.example.agentweb.app.harness.HarnessArtifactQueryService;
import com.example.agentweb.app.harness.HarnessAppService;
import com.example.agentweb.app.harness.HarnessExecutionService;
import com.example.agentweb.app.harness.HarnessMutationResult;
import com.example.agentweb.app.harness.HarnessRunQueryService;
import com.example.agentweb.app.harness.HarnessRunSummaryView;
import com.example.agentweb.app.harness.HarnessRunView;
import com.example.agentweb.app.harness.InvalidHarnessIdempotencyKeyException;
import com.example.agentweb.app.harness.RegisterHarnessArtifactCommand;
import com.example.agentweb.app.harness.StartHarnessDeploymentCommand;
import com.example.agentweb.domain.harness.ArtifactClassification;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactType;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.interfaces.dto.HarnessApprovalRequest;
import com.example.agentweb.interfaces.dto.HarnessArtifactRequest;
import com.example.agentweb.interfaces.dto.HarnessAnswerRequest;
import com.example.agentweb.interfaces.dto.HarnessCancelRequest;
import com.example.agentweb.interfaces.dto.HarnessDeploymentApprovalRequest;
import com.example.agentweb.interfaces.dto.HarnessDeploymentReconcileRequest;
import com.example.agentweb.interfaces.dto.HarnessDeploymentStartRequest;
import com.example.agentweb.interfaces.dto.HarnessGateRequest;
import com.example.agentweb.interfaces.dto.HarnessQuestionRequest;
import com.example.agentweb.interfaces.dto.HarnessRunCreateRequest;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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
    private final HarnessDeploymentService deploymentService;
    private final HarnessDeploymentReadinessQueryService deploymentReadinessQueryService;
    private final DeploymentExecutionQueryService deploymentQueryService;
    private final HarnessArtifactQueryService artifactQueryService;

    public HarnessController(HarnessAppService appService, HarnessRunQueryService queryService,
                             HarnessExecutionService executionService,
                             HarnessDeploymentService deploymentService,
                             HarnessDeploymentReadinessQueryService deploymentReadinessQueryService,
                             DeploymentExecutionQueryService deploymentQueryService,
                             HarnessArtifactQueryService artifactQueryService) {
        this.appService = appService;
        this.queryService = queryService;
        this.executionService = executionService;
        this.deploymentService = deploymentService;
        this.deploymentReadinessQueryService = deploymentReadinessQueryService;
        this.deploymentQueryService = deploymentQueryService;
        this.artifactQueryService = artifactQueryService;
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
                request.getEnvironment(), definitionVersion, key, request.getOriginalRequirement()));
        return ResponseEntity.created(URI.create("/api/harness/runs/" + result.getRunId()))
                .body(result);
    }

    @GetMapping("/{runId}")
    public HarnessRunView find(@PathVariable("runId") String runId) {
        return queryService.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }

    @GetMapping
    public List<HarnessRunSummaryView> list() {
        return queryService.list();
    }

    @GetMapping("/{runId}/events")
    public List<HarnessRunView.EventView> events(@PathVariable("runId") String runId) {
        return find(runId).getEvents();
    }

    @GetMapping("/{runId}/artifacts/{artifactId}")
    public ResponseEntity<byte[]> artifactContent(@PathVariable("runId") String runId,
                                                  @PathVariable("artifactId") String artifactId) {
        HarnessArtifactContentView artifact = artifactQueryService.findLatest(runId, artifactId)
                .orElseThrow(() -> new HarnessArtifactNotFoundException(runId, artifactId));
        return contentResponse(artifact, true);
    }

    @GetMapping("/{runId}/report")
    public ResponseEntity<byte[]> finalReport(@PathVariable("runId") String runId) {
        HarnessArtifactContentView report = artifactQueryService.findFinalReport(runId)
                .orElseThrow(() -> new HarnessArtifactNotFoundException(runId, "FINAL_REPORT"));
        return contentResponse(report, false);
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
        return accepted(appService.evaluateGate(runId, stage(stage), request.getRule()));
    }

    @PostMapping("/{runId}/stages/{stage}/questions")
    public ResponseEntity<HarnessMutationResult> requestInput(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @Valid @RequestBody HarnessQuestionRequest request) {
        return accepted(appService.requestInput(runId, stage(stage), request.getQuestionId(),
                request.getQuestion(), request.getBlocking().booleanValue()));
    }

    @PostMapping("/{runId}/questions/{questionId}/answer")
    public ResponseEntity<HarnessMutationResult> answerQuestion(
            @PathVariable("runId") String runId,
            @PathVariable("questionId") String questionId,
            @Valid @RequestBody HarnessAnswerRequest request) {
        return accepted(appService.answerQuestion(runId, questionId, request.getAnswer()));
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
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessApprovalRequest request) {
        return accepted(appService.approve(runId, stage(stage),
                request.getArtifactBaselineHash(), request.getReason(),
                requireIdempotencyKey(idempotencyKey)));
    }

    @PostMapping("/{runId}/stages/{stage}/reject")
    public ResponseEntity<HarnessMutationResult> reject(
            @PathVariable("runId") String runId,
            @PathVariable("stage") String stage,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessApprovalRequest request) {
        return accepted(appService.reject(runId, stage(stage),
                request.getArtifactBaselineHash(), request.getReason(),
                requireIdempotencyKey(idempotencyKey)));
    }

    @PostMapping("/{runId}/stages/DEPLOYMENT/deployment-approval")
    public ResponseEntity<HarnessMutationResult> approveDeployment(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessDeploymentApprovalRequest request) {
        return accepted(appService.approveDeployment(runId, request.getInputBaselineHash(),
                request.getReason(), requireIdempotencyKey(idempotencyKey)));
    }

    @GetMapping("/{runId}/stages/DEPLOYMENT/deployment-readiness")
    public HarnessDeploymentReadinessView deploymentReadiness(
            @PathVariable("runId") String runId) {
        return deploymentReadinessQueryService.find(runId);
    }

    @PostMapping("/{runId}/stages/DEPLOYMENT/deployments")
    public ResponseEntity<HarnessDeploymentResult> startDeployment(
            @PathVariable("runId") String runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody HarnessDeploymentStartRequest request) {
        HarnessDeploymentResult result = deploymentService.start(
                new StartHarnessDeploymentCommand(runId, request.getTemplateId(),
                        request.getApprovedInputBaselineHash(),
                        requireIdempotencyKey(idempotencyKey)));
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/{runId}/deployments")
    public List<DeploymentExecutionView> deployments(@PathVariable("runId") String runId) {
        return deploymentQueryService.listByRun(runId);
    }

    @GetMapping("/{runId}/deployments/{executionId}")
    public DeploymentExecutionView deployment(@PathVariable("runId") String runId,
                                              @PathVariable("executionId") String executionId) {
        return deploymentQueryService.find(runId, executionId)
                .orElseThrow(() -> new HarnessDeploymentExecutionNotFoundException(
                        runId, executionId));
    }

    @PostMapping("/{runId}/deployments/{executionId}/reconcile")
    public ResponseEntity<HarnessDeploymentResult> reconcileDeployment(
            @PathVariable("runId") String runId,
            @PathVariable("executionId") String executionId,
            @Valid @RequestBody HarnessDeploymentReconcileRequest request) {
        return ResponseEntity.accepted().body(deploymentService.reconcileAsFailed(
                runId, executionId, request.getReason()));
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

    private ResponseEntity<byte[]> contentResponse(HarnessArtifactContentView artifact,
                                                   boolean attachment) {
        ContentDisposition disposition = attachment
                ? ContentDisposition.attachment().filename(filename(artifact)).build()
                : ContentDisposition.inline().filename(filename(artifact)).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(artifact.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .eTag('"' + artifact.getSha256() + '"')
                .body(artifact.copyContent());
    }

    private String filename(HarnessArtifactContentView artifact) {
        String extension;
        if ("application/json".equals(artifact.getContentType())) {
            extension = ".json";
        } else if ("text/markdown".equals(artifact.getContentType())) {
            extension = ".md";
        } else if ("text/plain".equals(artifact.getContentType())) {
            extension = ".txt";
        } else {
            extension = ".bin";
        }
        return artifact.getArtifactType().toLowerCase(java.util.Locale.ROOT)
                + "-v" + artifact.getVersion() + extension;
    }
}
