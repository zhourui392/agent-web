package com.example.agentweb.config.workbench;

import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Workbench 发布策略的生产装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class WorkbenchReleaseConfiguration {

    @Bean
    public WorkbenchReleasePolicy workbenchReleasePolicy(
            WorkbenchReleaseProperties properties) {
        properties.validate();
        WorkbenchReleaseProperties.HighImpact highImpact =
                properties.getHighImpact();
        return new WorkbenchReleasePolicy(
                properties.isEnabled(),
                properties.isCreateEnabled(),
                properties.isWriteRunEnabled(),
                highImpact.isCommitEnabled(),
                highImpact.isPushEnabled(),
                highImpact.isLocalDeployEnabled(),
                highImpact.isProductionWriteEnabled());
    }
}
