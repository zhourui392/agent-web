package com.example.agentweb.app.harness;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactContentSanitizer;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessGeneratedArtifact;
import com.example.agentweb.domain.harness.GateArtifact;
import com.example.agentweb.domain.harness.GateDecision;
import com.example.agentweb.domain.harness.GateEvaluationContext;
import com.example.agentweb.domain.harness.HarnessDeterministicGatePolicy;
import com.example.agentweb.domain.harness.HarnessCommandId;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.HarnessStage;
import com.example.agentweb.domain.harness.StageContract;
import com.example.agentweb.domain.worktree.WorkspacePathPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

/**
 * Harness M1 事务编排，不承载阶段状态和审批业务规则。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessAppServiceImpl implements HarnessAppService {

    private final HarnessRunRepository repository;
    private final ArtifactStore artifactStore;
    private final WorkspacePathPolicy workspacePathPolicy;
    private final ArtifactContentSanitizer contentSanitizer;
    private final WorkspaceBaselineGateway workspaceBaselineGateway;
    private final CurrentUserProvider currentUserProvider;
    private final HarnessIdGenerator idGenerator;
    private final HarnessDeterministicGatePolicy gatePolicy;
    private final Clock clock;

    public HarnessAppServiceImpl(HarnessRunRepository repository, ArtifactStore artifactStore,
                                 ArtifactContentSanitizer contentSanitizer,
                                 WorkspacePathPolicy workspacePathPolicy,
                                 WorkspaceBaselineGateway workspaceBaselineGateway,
                                 CurrentUserProvider currentUserProvider,
                                 HarnessIdGenerator idGenerator,
                                 HarnessDeterministicGatePolicy gatePolicy, Clock clock) {
        this.repository = repository;
        this.artifactStore = artifactStore;
        this.contentSanitizer = contentSanitizer;
        this.workspacePathPolicy = workspacePathPolicy;
        this.workspaceBaselineGateway = workspaceBaselineGateway;
        this.currentUserProvider = currentUserProvider;
        this.idGenerator = idGenerator;
        this.gatePolicy = gatePolicy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public HarnessMutationResult create(CreateHarnessRunCommand command) {
        String realWorkingDir = workspacePathPolicy.requireExistingDirectory(command.getWorkingDir());
        String actor = currentUserProvider.currentUserId();
        Optional<HarnessRun> existing = repository.findByCreatorAndIdempotencyKey(
                actor, command.getIdempotencyKey());
        if (existing.isPresent()) {
            HarnessRun run = existing.get();
            run.requireMatchingCreation(command.getTitle(), realWorkingDir, command.getAgentType(),
                    command.getEnvironment(), command.getDefinitionVersion());
            return HarnessMutationResult.from(run, true);
        }
        WorkspaceBaseline workspaceBaseline = workspaceBaselineGateway.capture(realWorkingDir);
        HarnessRun run = HarnessRun.create(idGenerator.nextId(), command.getTitle(),
                realWorkingDir, command.getAgentType(), command.getEnvironment(),
                command.getDefinitionVersion(), actor, command.getIdempotencyKey(),
                workspaceBaseline, StageContract.mvpDefaults(), clock.instant());
        ArtifactContent originalRequirement = contentSanitizer.sanitize("text/markdown",
                ArtifactContent.from(command.getOriginalRequirement()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        ArtifactDescriptor descriptor = run.registerOriginalRequirement(
                idGenerator.nextId(), originalRequirement, actor, clock.instant());
        artifactStore.store(descriptor, originalRequirement);
        repository.add(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult startStage(String runId, HarnessStage stage,
                                            String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        WorkspaceBaseline baseline = workspaceBaselineGateway.capture(run.getWorkingDir());
        if (run.startStage(stage, idempotencyKey,
                currentUserProvider.currentUserId(), clock.instant())) {
            HarnessGeneratedArtifact generated = run.captureImplementationBaseline(
                    stage, baseline, clock.instant());
            if (generated != null) {
                artifactStore.store(generated.getDescriptor(), generated.getContent());
            }
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult registerArtifact(RegisterHarnessArtifactCommand command) {
        HarnessRun run = requireRun(command.getRunId());
        ArtifactContent content = contentSanitizer.sanitize(command.getContentType(),
                ArtifactContent.from(command.getContent()));
        Instant now = clock.instant();
        ArtifactDescriptor descriptor = run.registerArtifact(
                command.getStage(), idGenerator.nextId(), command.getArtifactType(), content,
                command.getContentType(), command.getClassification(), currentUserProvider.currentUserId(),
                run.artifactSourceReferences(command.getStage()), now);
        artifactStore.store(descriptor, content);
        repository.update(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult recordGate(String runId, HarnessStage stage, String rule,
                                            boolean passed, List<String> evidenceReferences,
                                            String reason) {
        HarnessRun run = requireRun(runId);
        if (run.recordGate(stage, idGenerator.nextId(), rule, passed,
                evidenceReferences, reason, currentUserProvider.currentUserId(), clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult evaluateGate(String runId, HarnessStage stage, String rule) {
        HarnessRun run = requireRun(runId);
        List<GateArtifact> artifacts = new ArrayList<GateArtifact>();
        for (ArtifactDescriptor descriptor : run.gateArtifactDescriptors(stage)) {
            artifacts.add(new GateArtifact(descriptor, artifactStore.read(descriptor)));
        }
        GateDecision decision = gatePolicy.evaluate(rule, new GateEvaluationContext(
                stage, run.capabilityStageContract(stage), artifacts));
        run.recordGate(stage, idGenerator.nextId(), rule, decision.isPassed(),
                decision.getEvidenceReferences(), decision.getReason(),
                currentUserProvider.currentUserId(), clock.instant());
        repository.update(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult requestInput(String runId, HarnessStage stage, String questionId,
                                              String question, boolean blocking) {
        HarnessRun run = requireRun(runId);
        if (run.requestInput(stage, questionId, question, blocking,
                currentUserProvider.currentUserId(), clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult answerQuestion(String runId, String questionId, String answer) {
        HarnessRun run = requireRun(runId);
        if (run.answerQuestion(questionId, answer,
                currentUserProvider.currentUserId(), clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult requestApproval(String runId, HarnessStage stage) {
        HarnessRun run = requireRun(runId);
        run.submitForApproval(stage, currentUserProvider.currentUserId(), clock.instant());
        repository.update(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult approve(String runId, HarnessStage stage,
                                         String artifactBaselineHash, String reason,
                                         String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        if (run.approve(stage, HarnessCommandId.approval(runId, stage, "APPROVED", idempotencyKey),
                artifactBaselineHash,
                currentUserProvider.currentUserId(), reason, clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult reject(String runId, HarnessStage stage,
                                        String artifactBaselineHash, String reason,
                                        String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        if (run.reject(stage, HarnessCommandId.approval(runId, stage, "REJECTED", idempotencyKey),
                artifactBaselineHash,
                currentUserProvider.currentUserId(), reason, clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult retryStage(String runId, HarnessStage stage,
                                            String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        WorkspaceBaseline baseline = workspaceBaselineGateway.capture(run.getWorkingDir());
        if (run.retryStage(stage, idempotencyKey,
                currentUserProvider.currentUserId(), clock.instant())) {
            HarnessGeneratedArtifact generated = run.captureImplementationBaseline(
                    stage, baseline, clock.instant());
            if (generated != null) {
                artifactStore.store(generated.getDescriptor(), generated.getContent());
            }
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult approveDeployment(String runId, String inputBaselineHash,
                                                   String reason, String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        if (run.approveDeployment(HarnessCommandId.approval(runId, HarnessStage.DEPLOYMENT,
                        "LOCAL_DEPLOY", idempotencyKey), inputBaselineHash,
                currentUserProvider.currentUserId(), reason, clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    private HarnessRun requireRun(String runId) {
        return repository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }
}
