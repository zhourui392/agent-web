package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactContentSanitizer;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.DeploymentArtifactFactory;
import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentCommandTemplateCatalog;
import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionOutcome;
import com.example.agentweb.domain.harness.DeploymentExecutionRepository;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.DeploymentPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.GateArtifact;
import com.example.agentweb.domain.harness.RuntimeProducedArtifact;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

/**
 * local 部署 PREPARED、激活、终态和 Artifact 的事务组件。
 *
 * @author alex
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessDeploymentPreparer {

    private final HarnessRunRepository runRepository;
    private final DeploymentExecutionRepository executionRepository;
    private final DeploymentCommandTemplateCatalog templateCatalog;
    private final WorkspaceBaselineGateway baselineGateway;
    private final ArtifactStore artifactStore;
    private final ArtifactContentSanitizer contentSanitizer;
    private final HarnessIdGenerator idGenerator;
    private final DeploymentArtifactFactory artifactFactory;
    private final Clock clock;

    public HarnessDeploymentPreparer(HarnessRunRepository runRepository,
                                     DeploymentExecutionRepository executionRepository,
                                     DeploymentCommandTemplateCatalog templateCatalog,
                                     WorkspaceBaselineGateway baselineGateway,
                                     ArtifactStore artifactStore,
                                     ArtifactContentSanitizer contentSanitizer,
                                     HarnessIdGenerator idGenerator,
                                     DeploymentArtifactFactory artifactFactory, Clock clock) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.templateCatalog = templateCatalog;
        this.baselineGateway = baselineGateway;
        this.artifactStore = artifactStore;
        this.contentSanitizer = contentSanitizer;
        this.idGenerator = idGenerator;
        this.artifactFactory = artifactFactory;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public PreparedHarnessDeployment prepare(StartHarnessDeploymentCommand command) {
        DeploymentCommandTemplate template = templateCatalog.resolve(command.getTemplateId());
        Optional<DeploymentExecution> existing = executionRepository.findByIdempotencyKey(
                command.getRunId(), command.getIdempotencyKey());
        HarnessRun run = requireRun(command.getRunId());
        if (existing.isPresent()) {
            DeploymentExecution execution = existing.get();
            execution.requireSameRequest(command.getRunId(),
                    command.getApprovedInputBaselineHash(), template.reference());
            return new PreparedHarnessDeployment(spec(execution, run, template), true);
        }
        WorkspaceBaseline baseline = baselineGateway.capture(run.getWorkingDir());
        DeploymentPermit permit = run.authorizeDeployment(
                command.getApprovedInputBaselineHash(), baseline);
        Instant now = clock.instant();
        DeploymentExecution execution = DeploymentExecution.prepare(idGenerator.nextId(),
                command.getIdempotencyKey(), permit, template.reference(), now);
        run.recordDeploymentPrepared(execution.getExecutionId(), now);
        runRepository.update(run);
        executionRepository.add(execution);
        return new PreparedHarnessDeployment(spec(execution, run, template), false);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean activate(String executionId, WorkspaceBaseline currentBaseline) {
        DeploymentExecution execution = requireExecution(executionId);
        boolean started = execution.begin(currentBaseline, clock.instant());
        executionRepository.update(execution);
        if (!started && execution.requiresFailureProjection()) {
            HarnessRun run = requireRun(execution.getRunId());
            if (run.applyDeploymentExecutionOutcome(requireOutcome(execution), clock.instant())) {
                runRepository.update(run);
            }
        }
        return started;
    }

    @Transactional(rollbackFor = Exception.class)
    public void complete(String executionId, DeploymentOutcome outcome) {
        DeploymentExecution execution = requireExecution(executionId);
        Instant now = clock.instant();
        execution.complete(outcome, now);
        HarnessRun run = requireRun(execution.getRunId());
        List<ArtifactReference> sources = run.artifactSourceReferences(HarnessStage.DEPLOYMENT);
        List<GateArtifact> reportEvidence = new ArrayList<GateArtifact>();
        for (ArtifactDescriptor descriptor : run.deliveryReportArtifactDescriptors()) {
            reportEvidence.add(new GateArtifact(descriptor, artifactStore.read(descriptor)));
        }
        for (RuntimeProducedArtifact artifact : artifactFactory.create(
                execution, outcome, reportEvidence)) {
            ArtifactContent sanitized = contentSanitizer.sanitize(
                    artifact.getContentType(), artifact.getContent());
            ArtifactDescriptor descriptor = run.registerArtifact(HarnessStage.DEPLOYMENT,
                    artifact.getArtifactId(), artifact.getArtifactType(), sanitized,
                    artifact.getContentType(), artifact.getClassification(), "harness-deployment",
                    sources, now);
            artifactStore.store(descriptor, sanitized);
        }
        run.applyDeploymentExecutionOutcome(requireOutcome(execution), now);
        executionRepository.update(execution);
        runRepository.update(run);
    }

    @Transactional(rollbackFor = Exception.class)
    public void fail(String executionId, String reason) {
        DeploymentExecution execution = requireExecution(executionId);
        execution.fail(reason, clock.instant());
        HarnessRun run = requireRun(execution.getRunId());
        run.applyDeploymentExecutionOutcome(requireOutcome(execution), clock.instant());
        executionRepository.update(execution);
        runRepository.update(run);
    }

    @Transactional(readOnly = true)
    public HarnessDeploymentResult result(String executionId, boolean duplicated) {
        DeploymentExecution execution = requireExecution(executionId);
        return new HarnessDeploymentResult(execution.getExecutionId(), execution.getRunId(),
                execution.getStatus().name(), duplicated);
    }

    @Transactional(rollbackFor = Exception.class)
    public HarnessDeploymentResult reconcileAsFailed(String runId, String executionId, String reason) {
        DeploymentExecution execution = requireExecution(executionId);
        execution.requireRun(runId);
        Instant now = clock.instant();
        execution.reconcileAsFailed(reason, now);
        HarnessRun run = requireRun(execution.getRunId());
        run.applyDeploymentExecutionOutcome(requireOutcome(execution), now);
        executionRepository.update(execution);
        runRepository.update(run);
        return new HarnessDeploymentResult(execution.getExecutionId(), execution.getRunId(),
                execution.getStatus().name(), false);
    }

    private DeploymentExecution requireExecution(String executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "deployment execution not found: " + executionId));
    }

    private DeploymentExecutionOutcome requireOutcome(DeploymentExecution execution) {
        return execution.outcome().orElseThrow(() -> new IllegalStateException(
                "deployment execution has no terminal outcome"));
    }

    private HarnessRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }

    private DeploymentExecutionSpec spec(DeploymentExecution execution, HarnessRun run,
                                         DeploymentCommandTemplate template) {
        return new DeploymentExecutionSpec(execution.getExecutionId(), run.getId(),
                run.getWorkingDir(), template);
    }
}
