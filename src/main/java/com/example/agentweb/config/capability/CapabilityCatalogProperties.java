package com.example.agentweb.config.capability;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 可信 Capability Catalog 根配置。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.capability")
@Getter
@Setter
public class CapabilityCatalogProperties {

    private String ruleRoot = "src/main/resources/capability/rules";
    private String platformSkillRoot = "src/main/resources/capability/skills";
    private String approvedUserSkillRoot;
    private String workspaceSkillRoot;
    private String mcpServerRoot = "src/main/resources/capability/mcp-servers";
}
