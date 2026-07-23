package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import com.example.agentweb.app.harness.port.AgentRuntimeGateway;
import com.example.agentweb.app.harness.port.AgentRuntimeStartException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 非事务执行外壳；只有事务准备方法返回后才触发外部进程副作用。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
@SuppressWarnings("PMD.ServiceOrDaoClassShouldEndWithImplRule")
public class HarnessExecutionLauncher implements HarnessExecutionService {

    private final HarnessExecutionPreparer preparer;
    private final HarnessRuntimeEventRecorder runtimeEventService;
    private final AgentRuntimeGateway runtimeGateway;

    public HarnessExecutionLauncher(HarnessExecutionPreparer preparer,
                                    HarnessRuntimeEventRecorder runtimeEventService,
                                    AgentRuntimeGateway runtimeGateway) {
        this.preparer = preparer;
        this.runtimeEventService = runtimeEventService;
        this.runtimeGateway = runtimeGateway;
    }

    @Override
    public HarnessExecutionResult start(StartHarnessExecutionCommand command) {
        PreparedHarnessExecution prepared = preparer.prepare(command);
        AgentExecutionSpec spec = prepared.getSpec();
        if (preparer.activate(spec.getExecutionId())) {
            try {
                runtimeGateway.start(spec, runtimeEventService);
            } catch (AgentRuntimeStartException ex) {
                runtimeEventService.recordStartFailure(spec.getExecutionId(), safeMessage(ex),
                        ex.isTemporaryConfigCleaned());
            } catch (RuntimeException ex) {
                runtimeEventService.recordStartFailure(spec.getExecutionId(), safeMessage(ex), false);
            }
        }
        return preparer.result(spec.getExecutionId(), prepared.isDuplicated());
    }

    @Override
    public HarnessMutationResult cancel(String runId, String reason) {
        PreparedHarnessCancellation prepared = preparer.prepareCancellation(runId, reason);
        if (prepared.getDirective().requiresRuntimeCancellation()) {
            runtimeGateway.cancel(prepared.getDirective().getExecutionId());
        }
        return prepared.getResult();
    }

    private String safeMessage(RuntimeException error) {
        String name = error.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "runtime start failed" : name;
    }
}
