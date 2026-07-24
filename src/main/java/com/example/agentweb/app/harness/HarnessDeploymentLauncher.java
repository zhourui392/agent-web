package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import com.example.agentweb.app.harness.port.DeploymentGateway;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 非事务部署外壳；PREPARED 提交后才做第二次 Git Preflight 和外部命令。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessDeploymentLauncher implements HarnessDeploymentService {

    private final HarnessDeploymentPreparer preparer;
    private final WorkspaceBaselineGateway baselineGateway;
    private final DeploymentGateway deploymentGateway;

    public HarnessDeploymentLauncher(HarnessDeploymentPreparer preparer,
                                     WorkspaceBaselineGateway baselineGateway,
                                     DeploymentGateway deploymentGateway) {
        this.preparer = preparer;
        this.baselineGateway = baselineGateway;
        this.deploymentGateway = deploymentGateway;
    }

    @Override
    public HarnessDeploymentResult start(StartHarnessDeploymentCommand command) {
        PreparedHarnessDeployment prepared = preparer.prepare(command);
        DeploymentExecutionSpec spec = prepared.getSpec();
        if (!prepared.isDuplicated()) {
            WorkspaceBaseline current = baselineGateway.capture(spec.getWorkingDir());
            if (preparer.activate(spec.getExecutionId(), current)) {
                try {
                    DeploymentOutcome outcome = deploymentGateway.execute(spec);
                    preparer.complete(spec.getExecutionId(), outcome);
                } catch (RuntimeException ex) {
                    preparer.fail(spec.getExecutionId(), safeMessage(ex));
                }
            }
        }
        return preparer.result(spec.getExecutionId(), prepared.isDuplicated());
    }

    @Override
    public HarnessDeploymentResult reconcileAsFailed(String runId, String executionId,
                                                     String reason) {
        return preparer.reconcileAsFailed(runId, executionId, reason);
    }

    private String safeMessage(RuntimeException error) {
        String name = error.getClass().getSimpleName();
        return name == null || name.isEmpty() ? "deployment gateway failed" : name;
    }
}
