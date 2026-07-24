package com.example.agentweb.infra.harness;

import com.example.agentweb.app.harness.port.DeploymentExecutionSpec;
import com.example.agentweb.config.harness.HarnessDeploymentProperties;
import com.example.agentweb.domain.harness.DeploymentCommand;
import com.example.agentweb.domain.harness.DeploymentCommandTemplate;
import com.example.agentweb.domain.harness.DeploymentOutcome;
import com.example.agentweb.domain.harness.DeploymentStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * local 部署命令的顺序、失败停止、超时、输出上限和脱敏测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class ProcessDeploymentGatewayTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunControlledStepsWithoutExecutingRollback() throws Exception {
        Path marker = tempDir.resolve("marker.txt");
        DeploymentCommandTemplate template = template(marker, null);
        ProcessDeploymentGateway gateway = gateway(5L, 1024L * 1024L);

        DeploymentOutcome outcome = gateway.execute(new DeploymentExecutionSpec(
                "deploy-1", "run-1", tempDir.toString(), template));

        assertTrue(outcome.isSuccessful());
        assertEquals(Arrays.asList("BUILD", "DEPLOY", "HEALTH_CHECK", "ACCEPTANCE"),
                Files.readAllLines(marker, StandardCharsets.UTF_8));
        assertFalse(Files.readAllLines(marker, StandardCharsets.UTF_8).contains("ROLLBACK"));
    }

    @Test
    void failedStepShouldStopAndRedactSensitiveOutput() throws Exception {
        Path marker = tempDir.resolve("failed-marker.txt");
        DeploymentCommandTemplate template = template(marker, DeploymentStep.DEPLOY);
        ProcessDeploymentGateway gateway = gateway(5L, 1024L * 1024L);

        DeploymentOutcome outcome = gateway.execute(new DeploymentExecutionSpec(
                "deploy-2", "run-1", tempDir.toString(), template));

        assertFalse(outcome.isSuccessful());
        assertEquals(DeploymentStep.DEPLOY,
                outcome.getResults().get(outcome.getResults().size() - 1).getStep());
        assertFalse(outcome.getResults().get(1).getOutputSummary().contains("super-secret-token"));
        assertEquals(Arrays.asList("BUILD", "DEPLOY"),
                Files.readAllLines(marker, StandardCharsets.UTF_8));
    }

    private ProcessDeploymentGateway gateway(long timeout, long maxBytes) {
        HarnessDeploymentProperties properties = new HarnessDeploymentProperties();
        properties.setCommandTimeoutSeconds(timeout);
        properties.setMaxOutputBytes(maxBytes);
        return new ProcessDeploymentGateway(properties, Clock.systemUTC());
    }

    private DeploymentCommandTemplate template(Path marker, DeploymentStep failing)
            throws Exception {
        Map<DeploymentStep, DeploymentCommand> commands =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        for (DeploymentStep step : DeploymentStep.values()) {
            int exit = step == failing ? 7 : 0;
            String output = step == failing ? "api_key=super-secret-token" : "ok";
            Path script = tempDir.resolve(step.name().toLowerCase() + ".sh");
            Files.write(script, ("#!/bin/sh\n"
                    + "printf '%s\\n' '" + step.name() + "' >> '" + marker + "'\n"
                    + "printf '%s\\n' '" + output + "'\n"
                    + "exit " + exit + "\n").getBytes(StandardCharsets.UTF_8));
            assertTrue(script.toFile().setExecutable(true));
            commands.put(step, new DeploymentCommand(step,
                    java.util.Collections.singletonList(script.toString())));
        }
        return new DeploymentCommandTemplate("local-default", "1", "local", commands);
    }
}
