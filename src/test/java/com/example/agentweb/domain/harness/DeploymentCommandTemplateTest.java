package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理员部署模板的 local、完整步骤和稳定 Hash 测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class DeploymentCommandTemplateTest {

    @Test
    void shouldRequireEveryControlledStepIncludingManualRollback() {
        DeploymentCommandTemplate template = new DeploymentCommandTemplate(
                "local-default", "1.0.0", "local", commands());

        assertEquals(5, template.getCommands().size());
        assertTrue(template.reference().isRollbackConfigured());
        assertEquals(64, template.getTemplateHash().length());

        Map<DeploymentStep, DeploymentCommand> missing = commands();
        missing.remove(DeploymentStep.ROLLBACK);
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommandTemplate(
                "local-default", "1.0.0", "local", missing));
        assertThrows(IllegalArgumentException.class, () -> new DeploymentCommandTemplate(
                "prod", "1.0.0", "production", commands()));
    }

    private Map<DeploymentStep, DeploymentCommand> commands() {
        Map<DeploymentStep, DeploymentCommand> commands =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        for (DeploymentStep step : DeploymentStep.values()) {
            commands.put(step, new DeploymentCommand(step,
                    Arrays.asList("safe-runner", step.name().toLowerCase())));
        }
        return commands;
    }
}
