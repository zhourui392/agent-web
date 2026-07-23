package com.example.agentweb.config;

import com.example.agentweb.app.harness.HarnessCapabilitySettings;
import com.example.agentweb.domain.harness.HarnessPromptAssembler;
import com.example.agentweb.domain.harness.SkillSelectionPolicy;
import com.example.agentweb.infra.harness.HarnessProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Harness M2 无框架依赖 Domain Policy 与应用配置装配。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Configuration
@ConditionalOnProperty(prefix = "agent.harness", name = "enabled", havingValue = "true")
public class HarnessCapabilityConfig {

    @Bean
    public SkillSelectionPolicy harnessSkillSelectionPolicy() {
        return new SkillSelectionPolicy();
    }

    @Bean
    public HarnessPromptAssembler harnessPromptAssembler() {
        return new HarnessPromptAssembler();
    }

    @Bean
    public HarnessCapabilitySettings harnessCapabilitySettings(HarnessProperties properties) {
        return new HarnessCapabilitySettings(properties.getCapabilityPolicyVersion(),
                properties.getPlatformSafety(), properties.getEnvironmentGuardrail());
    }
}
