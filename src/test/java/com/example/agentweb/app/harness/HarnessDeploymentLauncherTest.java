package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import com.example.agentweb.app.harness.port.DeploymentGateway;
import com.example.agentweb.app.harness.port.WorkspaceBaselineGateway;
import com.example.agentweb.domain.harness.DeploymentCommand;
import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.DeploymentStep;
import com.example.agentweb.domain.harness.WorkspaceBaseline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 部署外部副作用必须在 PREPARED 返回后发生的编排测试。
 *
 * @author alex
 * @since 2026-07-23
 */
@ExtendWith(MockitoExtension.class)
class HarnessDeploymentLauncherTest {

    @Mock
    private HarnessDeploymentPreparer preparer;
    @Mock
    private WorkspaceBaselineGateway baselineGateway;
    @Mock
    private DeploymentGateway gateway;

    @Test
    void shouldExecuteGatewayOnlyAfterPrepareAndActivation() {
        DeploymentExecutionSpec spec = new DeploymentExecutionSpec(
                "deploy-1", "run-1", "/workspace", template());
        StartHarnessDeploymentCommand command = command();
        WorkspaceBaseline baseline = baseline();
        when(preparer.prepare(command)).thenReturn(new PreparedHarnessDeployment(spec, false));
        when(baselineGateway.capture("/workspace")).thenReturn(baseline);
        when(preparer.activate("deploy-1", baseline)).thenReturn(true);
        DeploymentOutcome outcome = org.mockito.Mockito.mock(DeploymentOutcome.class);
        when(gateway.execute(spec)).thenReturn(outcome);
        when(preparer.result("deploy-1", false)).thenReturn(
                new HarnessDeploymentResult("deploy-1", "run-1", "SUCCEEDED", false));

        new HarnessDeploymentLauncher(preparer, baselineGateway, gateway).start(command);

        InOrder order = inOrder(preparer, baselineGateway, gateway);
        order.verify(preparer).prepare(command);
        order.verify(baselineGateway).capture("/workspace");
        order.verify(preparer).activate("deploy-1", baseline);
        order.verify(gateway).execute(spec);
        order.verify(preparer).complete("deploy-1", outcome);
    }

    @Test
    void duplicateOrFailedPreflightShouldNeverReplayGateway() {
        DeploymentExecutionSpec spec = new DeploymentExecutionSpec(
                "deploy-1", "run-1", "/workspace", template());
        StartHarnessDeploymentCommand command = command();
        when(preparer.prepare(command)).thenReturn(new PreparedHarnessDeployment(spec, true));
        when(preparer.result("deploy-1", true)).thenReturn(
                new HarnessDeploymentResult("deploy-1", "run-1", "PREPARED", true));

        new HarnessDeploymentLauncher(preparer, baselineGateway, gateway).start(command);

        verify(gateway, never()).execute(spec);
        verify(baselineGateway, never()).capture("/workspace");
    }

    private StartHarnessDeploymentCommand command() {
        return new StartHarnessDeploymentCommand("run-1", "local-default", hash('a'), "key-1");
    }

    private WorkspaceBaseline baseline() {
        return WorkspaceBaseline.capture("/workspace", "feat/m4",
                "0123456789012345678901234567890123456789", false, hash('b'),
                Instant.parse("2026-07-23T12:00:00Z"));
    }

    private DeploymentCommandTemplate template() {
        Map<DeploymentStep, DeploymentCommand> commands =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        for (DeploymentStep step : DeploymentStep.values()) {
            commands.put(step, new DeploymentCommand(step, Arrays.asList("runner", step.name())));
        }
        return new DeploymentCommandTemplate("local-default", "1", "local", commands);
    }

    private String hash(char value) {
        return String.join("", Collections.nCopies(64, String.valueOf(value)));
    }
}
