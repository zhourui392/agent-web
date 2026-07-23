package com.example.agentweb.app.agentrun;

import com.example.agentweb.config.EnvProperties;
import org.springframework.stereotype.Component;

/**
 * Adds environment prompt configured by agent.envs.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
public class EnvPromptContributor implements PromptContributor {

    private final EnvProperties envProperties;

    public EnvPromptContributor(EnvProperties envProperties) {
        this.envProperties = envProperties;
    }

    @Override
    public void append(PromptAssembly assembly) {
        String env = assembly.getContext().getEnv();
        if (env == null || env.trim().isEmpty()) {
            return;
        }
        EnvProperties.EnvEntry entry = envProperties.findByKey(env.trim());
        if (entry == null || entry.getPrompt() == null || entry.getPrompt().trim().isEmpty()) {
            return;
        }
        assembly.addPart(PromptPartType.ENV, "Environment Guardrail", entry.getPrompt());
        assembly.markEnvGuardrailApplied();
    }
}
