package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactReference;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactContentSanitizer;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.RuntimeArtifactBundle;
import com.example.agentweb.domain.harness.RuntimeProducedArtifact;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionOutcome;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import com.example.agentweb.domain.harness.ImplementationEvidenceFactory;
import com.example.agentweb.domain.harness.ImplementationCommandEvidenceFactory;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.domain.harness.WorkspaceChangeEvidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Runtime 归一化事件的事务入口；业务终态委托两个聚合自身处理。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessRuntimeEventService implements HarnessRuntimeEventRecorder {

    private final RuntimeExecutionRepository executionRepository;
    private final HarnessRunRepository runRepository;
    private final ArtifactStore artifactStore;
    private final ArtifactContentSanitizer contentSanitizer;
    private final WorkspaceBaselineGateway workspaceBaselineGateway;
    private final ImplementationEvidenceFactory implementationEvidenceFactory;
    private final ImplementationCommandEvidenceFactory implementationCommandEvidenceFactory;
    private final Clock clock;

    public HarnessRuntimeEventService(RuntimeExecutionRepository executionRepository,
                                      HarnessRunRepository runRepository,
                                      ArtifactStore artifactStore,
                                      ArtifactContentSanitizer contentSanitizer,
                                      WorkspaceBaselineGateway workspaceBaselineGateway,
                                      ImplementationEvidenceFactory implementationEvidenceFactory,
                                      ImplementationCommandEvidenceFactory
                                              implementationCommandEvidenceFactory,
                                      Clock clock) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
        this.artifactStore = artifactStore;
        this.contentSanitizer = contentSanitizer;
        this.workspaceBaselineGateway = workspaceBaselineGateway;
        this.implementationEvidenceFactory = implementationEvidenceFactory;
        this.implementationCommandEvidenceFactory = implementationCommandEvidenceFactory;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(RuntimeEvent event) {
        RuntimeExecution execution = requireExecution(event.getExecutionId());
        RuntimeExecutionSignal signal = execution.enforceArtifactBundle(
                event.getSignal(), event.getArtifactBundle());
        if (!execution.apply(signal)) {
            return;
        }
        executionRepository.appendEvent(new RuntimeExecutionEvent(execution.getExecutionId(),
                signal.getSequence(), signal.getType(), event.getSummary(),
                signal.getEvidenceReference(), signal.getOccurredAt()));
        executionRepository.update(execution);
        HarnessRun run = runRepository.findById(execution.getRunId())
                .orElseThrow(() -> new HarnessRunNotFoundException(execution.getRunId()));
        Optional<RuntimeExecutionOutcome> outcome = execution.outcome();
        if (outcome.isPresent() && outcome.get().producesArtifacts()) {
            registerBundle(run, execution,
                    enrichImplementationEvidence(run, event),
                    signal.getOccurredAt());
        }
        if (outcome.isPresent()
                && run.applyRuntimeExecutionOutcome(outcome.get(), signal.getOccurredAt())) {
            runRepository.update(run);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void recordStartFailure(String executionId, String reason,
                                   boolean temporaryConfigCleaned) {
        RuntimeExecution execution = requireExecution(executionId);
        RuntimeExecutionSignal signal = RuntimeExecutionSignal.failed(
                execution.getLastEventSequence() + 1L, null, reason,
                temporaryConfigCleaned, clock.instant());
        if (execution.apply(signal)) {
            executionRepository.appendEvent(new RuntimeExecutionEvent(executionId,
                    signal.getSequence(), signal.getType(), reason, null, signal.getOccurredAt()));
            executionRepository.update(execution);
            HarnessRun run = runRepository.findById(execution.getRunId())
                    .orElseThrow(() -> new HarnessRunNotFoundException(execution.getRunId()));
            RuntimeExecutionOutcome outcome = execution.outcome()
                    .orElseThrow(() -> new IllegalStateException(
                            "failed runtime execution has no terminal outcome"));
            if (run.applyRuntimeExecutionOutcome(outcome, signal.getOccurredAt())) {
                runRepository.update(run);
            }
        }
    }

    private RuntimeExecution requireExecution(String executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "runtime execution not found: " + executionId));
    }

    private void registerBundle(HarnessRun run, RuntimeExecution execution,
                                RuntimeArtifactBundle bundle, java.time.Instant occurredAt) {
        List<ArtifactReference> sources = run.artifactSourceReferences(execution.getStage());
        for (RuntimeProducedArtifact artifact : bundle.getArtifacts()) {
            ArtifactContent sanitized = contentSanitizer.sanitize(
                    artifact.getContentType(), artifact.getContent());
            String suggestedId = "runtime-" + execution.getExecutionId() + "-"
                    + artifact.getArtifactType().name().toLowerCase(Locale.ROOT);
            ArtifactDescriptor descriptor = run.registerArtifact(execution.getStage(), suggestedId,
                    artifact.getArtifactType(), sanitized, artifact.getContentType(),
                    artifact.getClassification(), "harness-runtime", sources, occurredAt);
            artifactStore.store(descriptor, sanitized);
        }
    }

    private RuntimeArtifactBundle enrichImplementationEvidence(HarnessRun run,
                                                               RuntimeEvent event) {
        RuntimeArtifactBundle bundle = event.getArtifactBundle();
        ArtifactDescriptor baselineDescriptor = run.implementationBaselineArtifact(bundle);
        if (baselineDescriptor == null) {
            return bundle;
        }
        WorkspaceBaseline baseline = implementationEvidenceFactory.readBaseline(
                artifactStore.read(baselineDescriptor));
        WorkspaceChangeEvidence evidence = workspaceBaselineGateway.captureChanges(
                run.getWorkingDir(), baseline);
        RuntimeArtifactBundle gitEnriched = implementationEvidenceFactory.enrich(bundle, evidence);
        return implementationCommandEvidenceFactory.enrich(
                gitEnriched, event.getCommandObservations());
    }
}
