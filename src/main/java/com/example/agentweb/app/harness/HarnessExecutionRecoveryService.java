package com.example.agentweb.app.harness;

import com.example.agentweb.domain.harness.DeploymentExecution;
import com.example.agentweb.domain.harness.DeploymentExecutionRepository;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionOutcome;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionSignalType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 启动恢复时关闭不明外部动作：Runtime 标 LOST，部署进入人工对账，绝不自动重放。
 *
 * @author alex
 * @since 2026-07-23
 */
@Service
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessExecutionRecoveryService {

    private static final String REASON = "application restarted before external action completed";

    private final RuntimeExecutionRepository runtimeRepository;
    private final DeploymentExecutionRepository deploymentRepository;
    private final HarnessRunRepository runRepository;
    private final Clock clock;

    public HarnessExecutionRecoveryService(RuntimeExecutionRepository runtimeRepository,
                                           DeploymentExecutionRepository deploymentRepository,
                                           HarnessRunRepository runRepository, Clock clock) {
        this.runtimeRepository = runtimeRepository;
        this.deploymentRepository = deploymentRepository;
        this.runRepository = runRepository;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = Exception.class)
    public void recoverUnfinishedExternalActions() {
        for (RuntimeExecution execution : runtimeRepository.findUnfinished()) {
            if (execution.markLostAfterRestart(REASON, clock.instant())) {
                runtimeRepository.appendEvent(new RuntimeExecutionEvent(
                        execution.getExecutionId(), execution.getLastEventSequence(),
                        RuntimeExecutionSignalType.LOST, REASON, null, clock.instant()));
                runtimeRepository.update(execution);
                HarnessRun run = requireRun(execution.getRunId());
                RuntimeExecutionOutcome outcome = execution.outcome()
                        .orElseThrow(() -> new IllegalStateException(
                                "lost runtime execution has no terminal outcome"));
                if (run.applyRuntimeExecutionOutcome(outcome, clock.instant())) {
                    runRepository.update(run);
                }
            }
        }
        for (DeploymentExecution execution : deploymentRepository.findUnfinished()) {
            if (execution.requireReconciliation(REASON, clock.instant())) {
                deploymentRepository.update(execution);
            }
        }
    }

    private HarnessRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new HarnessRunNotFoundException(runId));
    }
}
