package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.RuntimeEvent;
import com.example.agentweb.domain.harness.HarnessRun;
import com.example.agentweb.domain.harness.HarnessRunNotFoundException;
import com.example.agentweb.domain.harness.HarnessRunRepository;
import com.example.agentweb.domain.harness.RuntimeExecution;
import com.example.agentweb.domain.harness.RuntimeExecutionEvent;
import com.example.agentweb.domain.harness.RuntimeExecutionRepository;
import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

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
    private final Clock clock;

    public HarnessRuntimeEventService(RuntimeExecutionRepository executionRepository,
                                      HarnessRunRepository runRepository, Clock clock) {
        this.executionRepository = executionRepository;
        this.runRepository = runRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void onEvent(RuntimeEvent event) {
        RuntimeExecution execution = requireExecution(event.getExecutionId());
        if (!execution.apply(event.getSignal())) {
            return;
        }
        RuntimeExecutionSignal signal = event.getSignal();
        executionRepository.appendEvent(new RuntimeExecutionEvent(execution.getExecutionId(),
                signal.getSequence(), signal.getType(), event.getSummary(),
                signal.getEvidenceReference(), signal.getOccurredAt()));
        executionRepository.update(execution);
        HarnessRun run = runRepository.findById(execution.getRunId())
                .orElseThrow(() -> new HarnessRunNotFoundException(execution.getRunId()));
        if (run.applyRuntimeExecutionOutcome(execution, signal.getOccurredAt())) {
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
            if (run.applyRuntimeExecutionOutcome(execution, signal.getOccurredAt())) {
                runRepository.update(run);
            }
        }
    }

    private RuntimeExecution requireExecution(String executionId) {
        return executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "runtime execution not found: " + executionId));
    }
}
