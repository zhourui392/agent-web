package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.harness.ArtifactStore;
import com.example.agentweb.domain.harness.HarnessGeneratedArtifact;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.StageConversationTurn;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 在单事务内记录阶段消息并准备新的不可变 Attempt。
 *
 * @author alex
 * @since 2026-07-24
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessConversationPreparer {

    private final HarnessRunRepository repository;
    private final ArtifactStore artifactStore;
    private final WorkspaceBaselineGateway workspaceBaselineGateway;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public HarnessConversationPreparer(HarnessRunRepository repository,
                                       ArtifactStore artifactStore,
                                       WorkspaceBaselineGateway workspaceBaselineGateway,
                                       CurrentUserProvider currentUserProvider,
                                       Clock clock) {
        this.repository = repository;
        this.artifactStore = artifactStore;
        this.workspaceBaselineGateway = workspaceBaselineGateway;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public PreparedHarnessConversation prepare(StartHarnessConversationCommand command) {
        HarnessRun run = repository.findById(command.getRunId())
                .orElseThrow(() -> new HarnessRunNotFoundException(command.getRunId()));
        StageConversationTurn turn = run.prepareConversationTurn(
                command.getStage(), command.getIdempotencyKey(), command.getMessage(),
                currentUserProvider.currentUserId(), clock.instant());
        if (!turn.isDuplicated()) {
            if (turn.isAttemptOpened()) {
                WorkspaceBaseline baseline = workspaceBaselineGateway.capture(run.getWorkingDir());
                HarnessGeneratedArtifact generated = run.captureImplementationBaseline(
                        command.getStage(), baseline, clock.instant());
                if (generated != null) {
                    artifactStore.store(generated.getDescriptor(), generated.getContent());
                }
            }
            repository.update(run);
        }
        return new PreparedHarnessConversation(run.getId(), command.getStage(),
                turn.getAttemptNumber(), turn.isDuplicated());
    }
}
