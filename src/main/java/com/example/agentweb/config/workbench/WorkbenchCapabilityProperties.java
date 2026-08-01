package com.example.agentweb.config.workbench;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workbench Phase Capability Profile 的可信资源根配置。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.workbench.capability")
@Getter
@Setter
public class WorkbenchCapabilityProperties {

    private String profileRoot = "src/main/resources/workbench/profiles";
    private String policyVersion = "workbench-policy@1";
    private int maxAdditionalRuleChars = 4000;
    private boolean hotReloadEnabled = true;
}
