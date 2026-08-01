package com.example.agentweb.config.workbench;

import com.example.agentweb.app.workbench.capability.DefaultPhaseCapabilityOverrideResolver;
import com.example.agentweb.app.workbench.capability.PhaseCapabilityOverrideResolver;
import com.example.agentweb.domain.capability.McpServerCatalog;
import com.example.agentweb.domain.capability.RuleCatalog;
import com.example.agentweb.domain.capability.SkillCatalog;
import com.example.agentweb.domain.workbench.PhaseCapabilityBindingResolver;
import com.example.agentweb.domain.workbench.PhaseCapabilityPreviewResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Workbench Capability 应用端口装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class WorkbenchCapabilityConfiguration {

    @Bean
    public PhaseCapabilityOverrideResolver phaseCapabilityOverrideResolver(
            WorkbenchCapabilityProperties properties) {
        return new DefaultPhaseCapabilityOverrideResolver(
                properties.getMaxAdditionalRuleChars());
    }

    @Bean
    public PhaseCapabilityBindingResolver phaseCapabilityBindingResolver(
            RuleCatalog ruleCatalog, SkillCatalog skillCatalog,
            McpServerCatalog mcpServerCatalog) {
        return new PhaseCapabilityBindingResolver(
                ruleCatalog, skillCatalog, mcpServerCatalog);
    }

    @Bean
    public PhaseCapabilityPreviewResolver phaseCapabilityPreviewResolver(
            PhaseCapabilityBindingResolver bindingResolver,
            WorkbenchCapabilityProperties properties) {
        return new PhaseCapabilityPreviewResolver(
                bindingResolver, properties.getPolicyVersion());
    }
}
