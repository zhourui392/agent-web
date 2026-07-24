package com.example.agentweb.domain.harness;

import lombok.Getter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 管理员注册的 local 部署命令模板。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class DeploymentCommandTemplate {

    private final String templateId;
    private final String version;
    private final String environment;
    private final Map<DeploymentStep, DeploymentCommand> commands;
    private final String templateHash;

    public DeploymentCommandTemplate(String templateId, String version, String environment,
                                     Map<DeploymentStep, DeploymentCommand> commands) {
        this.templateId = DomainText.require(templateId, "deployment template id", 128);
        this.version = DomainText.require(version, "deployment template version", 64);
        String normalizedEnvironment = DomainText.require(
                environment, "deployment template environment", 32).toLowerCase();
        if (!"local".equals(normalizedEnvironment)) {
            throw new IllegalArgumentException("MVP deployment template must target local");
        }
        if (commands == null || commands.size() != DeploymentStep.values().length) {
            throw new IllegalArgumentException("deployment template must define every step");
        }
        Map<DeploymentStep, DeploymentCommand> copy =
                new EnumMap<DeploymentStep, DeploymentCommand>(DeploymentStep.class);
        StringBuilder canonical = new StringBuilder();
        canonical.append(this.templateId).append('\n').append(this.version)
                .append('\n').append(normalizedEnvironment).append('\n');
        for (DeploymentStep step : DeploymentStep.values()) {
            DeploymentCommand command = commands.get(step);
            if (command == null || command.getStep() != step) {
                throw new IllegalArgumentException(
                        "deployment template step command is missing: " + step);
            }
            copy.put(step, command);
            canonical.append(command.canonical()).append('\n');
        }
        this.environment = normalizedEnvironment;
        this.commands = Collections.unmodifiableMap(copy);
        this.templateHash = HarnessHashing.sha256(canonical.toString());
    }

    public DeploymentTemplateReference reference() {
        return new DeploymentTemplateReference(templateId, version, templateHash,
                commands.containsKey(DeploymentStep.ROLLBACK));
    }

    public DeploymentCommand command(DeploymentStep step) {
        DeploymentCommand command = commands.get(step);
        if (command == null) {
            throw new IllegalArgumentException("deployment template step is unavailable: " + step);
        }
        return command;
    }
}
