package com.example.agentweb.app.harness;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.harness.ArtifactContent;
import com.example.agentweb.domain.harness.ArtifactDescriptor;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.HarnessRun;
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
    private final CurrentUserProvider currentUserProvider;
    private final HarnessIdGenerator idGenerator;
    private final Clock clock;

    public HarnessAppServiceImpl(HarnessRunRepository repository, ArtifactStore artifactStore,
                                 WorkspacePathPolicy workspacePathPolicy,
                                 CurrentUserProvider currentUserProvider,
                                 HarnessIdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.artifactStore = artifactStore;
        this.workspacePathPolicy = workspacePathPolicy;
        this.currentUserProvider = currentUserProvider;
        this.idGenerator = idGenerator;
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
        HarnessRun run = HarnessRun.create(idGenerator.nextId(), command.getTitle(),
                realWorkingDir, command.getAgentType(), command.getEnvironment(),
                command.getDefinitionVersion(), actor, command.getIdempotencyKey(),
                StageContract.mvpDefaults(), clock.instant());
        repository.add(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult startStage(String runId, HarnessStage stage,
                                            String idempotencyKey) {
        HarnessRun run = requireRun(runId);
        if (run.startStage(stage, idempotencyKey,
                currentUserProvider.currentUserId(), clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult registerArtifact(RegisterHarnessArtifactCommand command) {
        HarnessRun run = requireRun(command.getRunId());
        ArtifactContent content = ArtifactContent.from(command.getContent());
        Instant now = clock.instant();
        ArtifactDescriptor descriptor = run.registerArtifact(
                command.getStage(), idGenerator.nextId(), command.getArtifactType(), content,
                command.getContentType(), command.getClassification(), currentUserProvider.currentUserId(),
                command.getSourceArtifacts(), now);
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
    public HarnessMutationResult requestApproval(String runId, HarnessStage stage) {
        HarnessRun run = requireRun(runId);
        run.submitForApproval(stage, currentUserProvider.currentUserId(), clock.instant());
        repository.update(run);
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult approve(String runId, HarnessStage stage,
                                         String artifactBaselineHash, String reason) {
        HarnessRun run = requireRun(runId);
        if (run.approve(stage, idGenerator.nextId(), artifactBaselineHash,
                currentUserProvider.currentUserId(), reason, clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult reject(String runId, HarnessStage stage,
                                        String artifactBaselineHash, String reason) {
        HarnessRun run = requireRun(runId);
        if (run.reject(stage, idGenerator.nextId(), artifactBaselineHash,
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
        if (run.retryStage(stage, idempotencyKey,
                currentUserProvider.currentUserId(), clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    @Override
    @Transactional
    public HarnessMutationResult cancel(String runId, String reason) {
        HarnessRun run = requireRun(runId);
        if (run.cancel(currentUserProvider.currentUserId(), reason, clock.instant())) {
            repository.update(run);
        }
        return HarnessMutationResult.from(run, false);
    }

    private HarnessRun requireRun(String runId) {
        return repository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }
}
