package com.example.agentweb.config.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Harness Artifact、Prompt、Skill 与 MCP 可信 Catalog 根配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConfigurationProperties(prefix = "agent.harness")
@Getter
@Setter
public class HarnessCatalogProperties {

    private String artifactRoot = "data/harness/artifacts";
    private String promptPackRoot = "src/main/resources/harness/prompt-packs";
    private String platformSkillRoot = "src/main/resources/harness/skills";
    private String approvedUserSkillRoot;
    private String workspaceSkillRoot;
    private String mcpServerRoot = "src/main/resources/harness/mcp-servers";
    private String deploymentTemplateRoot = "src/main/resources/harness/deployment-templates";
}
