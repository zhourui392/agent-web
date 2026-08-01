package com.example.agentweb.config.harness;

import com.example.agentweb.app.harness.HarnessRetirementPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Harness 退役窗口策略的生产装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class HarnessRetirementConfiguration {

    @Bean
    public HarnessRetirementPolicy harnessRetirementPolicy(
            HarnessRetirementProperties properties) {
        return new HarnessRetirementPolicy(
                properties.isCreationEnabled(),
                properties.isMutationEnabled(),
                properties.isExportEnabled());
    }
}
