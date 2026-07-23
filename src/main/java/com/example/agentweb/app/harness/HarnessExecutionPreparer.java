package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.harness.CapabilitySnapshot;
import com.example.agentweb.domain.harness.CapabilitySnapshotReference;
import com.example.agentweb.domain.harness.CapabilitySnapshotRepository;
import com.example.agentweb.domain.harness.CancellationDirective;
import com.example.agentweb.domain.harness.ExecutionPermit;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Snapshot/Run/RuntimeExecution 与取消意图的事务准备组件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessExecutionPreparer {

    private final HarnessRunRepository runRepository;
    private final CapabilitySnapshotRepository snapshotRepository;
    private final RuntimeExecutionRepository executionRepository;
    private final CurrentUserProvider currentUserProvider;
    private final HarnessIdGenerator idGenerator;
    private final Clock clock;

    public HarnessExecutionPreparer(HarnessRunRepository runRepository,
                                    CapabilitySnapshotRepository snapshotRepository,
                                    RuntimeExecutionRepository executionRepository,
                                    CurrentUserProvider currentUserProvider,
                                    HarnessIdGenerator idGenerator, Clock clock) {
        this.runRepository = runRepository;
        this.snapshotRepository = snapshotRepository;
        this.executionRepository = executionRepository;
        this.currentUserProvider = currentUserProvider;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    @Transactional(rollbackFor = Exception.class)
    public PreparedHarnessExecution prepare(StartHarnessExecutionCommand command) {
        Optional<RuntimeExecution> existing = executionRepository.findByIdempotencyKey(
                command.getRunId(), command.getIdempotencyKey());
        if (existing.isPresent()) {
            RuntimeExecution execution = existing.get();
            execution.requireSameStartRequest(command.getRunId(), command.getStage());
            HarnessRun run = requireRun(command.getRunId());
            CapabilitySnapshot snapshot = requireSnapshot(execution.getRunId(),
                    execution.getStage(), execution.getAttemptNumber());
            return new PreparedHarnessExecution(spec(run, snapshot, execution.getExecutionId()), true);
        }
        HarnessRun run = requireRun(command.getRunId());
        int attempt = run.capabilitySnapshotAttempt(command.getStage());
        CapabilitySnapshot snapshot = requireSnapshot(run.getId(), command.getStage(), attempt);
        ExecutionPermit permit = run.authorizeExecution(command.getStage(),
                CapabilitySnapshotReference.from(snapshot));
        Instant now = clock.instant();
        RuntimeExecution execution = RuntimeExecution.prepare(idGenerator.nextId(),
                command.getIdempotencyKey(), permit, run.capabilityRuntime(), now);
        run.bindExecution(execution.reference(), now);
        runRepository.update(run);
        executionRepository.add(execution);
        return new PreparedHarnessExecution(spec(run, snapshot, execution.getExecutionId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean activate(String executionId) {
        RuntimeExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "runtime execution not found: " + executionId));
        if (!execution.beginLaunch(clock.instant())) {
            return false;
        }
        executionRepository.update(execution);
        return true;
    }

    @Transactional(readOnly = true)
    public HarnessExecutionResult result(String executionId, boolean duplicated) {
        RuntimeExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "runtime execution not found: " + executionId));
        return new HarnessExecutionResult(execution.getExecutionId(), execution.getRunId(),
                execution.getStage(), execution.getStatus().name(), duplicated,
                execution.getAttemptNumber());
    }

    @Transactional(rollbackFor = Exception.class)
    public PreparedHarnessCancellation prepareCancellation(String runId, String reason) {
        HarnessRun run = requireRun(runId);
        CancellationDirective directive = run.requestCancellation(
                currentUserProvider.currentUserId(), reason, clock.instant());
        runRepository.update(run);
        if (directive.requiresRuntimeCancellation()) {
            RuntimeExecution execution = executionRepository.findById(directive.getExecutionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "active runtime execution not found: " + directive.getExecutionId()));
            execution.requestCancellation(currentUserProvider.currentUserId(), reason, clock.instant());
            executionRepository.update(execution);
        }
        return new PreparedHarnessCancellation(HarnessMutationResult.from(run, false), directive);
    }

    private HarnessRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }

    private CapabilitySnapshot requireSnapshot(String runId,
                                               com.example.agentweb.domain.harness.HarnessStage stage,
                                               int attempt) {
        return snapshotRepository.find(runId, stage, attempt)
                .orElseThrow(() -> new HarnessCapabilitySnapshotNotFoundException(
                        runId, stage.name(), attempt));
    }

    private AgentExecutionSpec spec(HarnessRun run, CapabilitySnapshot snapshot,
                                    String executionId) {
        return new AgentExecutionSpec(executionId, run.getId(), snapshot.getStage(),
                snapshot.getAttemptNumber(), snapshot.getRuntime(), run.getWorkingDir(),
                snapshot.getFinalPrompt(), snapshot.getSnapshotHash(), snapshot.getPromptHash(),
                snapshot.getSelectedMcpServers(), snapshot.getRuntimeEnforcementProfile(),
                snapshot.getWorkspaceRuntimeInventory());
    }
}
