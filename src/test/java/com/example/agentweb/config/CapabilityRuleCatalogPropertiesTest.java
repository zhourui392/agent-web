package com.example.agentweb.config;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
import com.example.agentweb.config.harness.HarnessCatalogProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 公共 Capability Catalog 与 Harness 兼容配置的默认值及环境变量隔离测试。
 *
 * @author alex
 * @since 2026-08-01
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class CapabilityRuleCatalogPropertiesTest {

    private static final String COMMON_RULE_ROOT =
            "src/main/resources/capability/rules";
    private static final String COMMON_SKILL_ROOT =
            "src/main/resources/capability/skills";
    private static final String COMMON_MCP_ROOT =
            "src/main/resources/capability/mcp-servers";

    @Test
    void javaDefaultsShouldUseTheCommonCatalogRoots() {
        CapabilityCatalogProperties capability =
                new CapabilityCatalogProperties();
        HarnessCatalogProperties harness = new HarnessCatalogProperties();

        assertEquals(COMMON_RULE_ROOT, capability.getRuleRoot());
        assertEquals(COMMON_SKILL_ROOT, capability.getPlatformSkillRoot());
        assertEquals(COMMON_MCP_ROOT, capability.getMcpServerRoot());
        assertEquals(COMMON_RULE_ROOT, harness.getPromptPackRoot());
        assertEquals(COMMON_SKILL_ROOT, harness.getPlatformSkillRoot());
        assertEquals(COMMON_MCP_ROOT, harness.getMcpServerRoot());
    }

    @Test
    void legacyHarnessOverridesShouldNotChangeThePublicCatalogRoots() {
        BoundCatalogProperties properties = bindApplicationProperties(
                "AGENT_HARNESS_PROMPT_PACK_ROOT=/legacy/harness/rules",
                "AGENT_HARNESS_PLATFORM_SKILL_ROOT=/legacy/harness/skills",
                "AGENT_HARNESS_MCP_SERVER_ROOT=/legacy/harness/mcp-servers");

        assertEquals(COMMON_RULE_ROOT, properties.capabilityRuleRoot);
        assertEquals(COMMON_SKILL_ROOT, properties.capabilitySkillRoot);
        assertEquals(COMMON_MCP_ROOT, properties.capabilityMcpRoot);
        assertEquals("/legacy/harness/rules",
                properties.harnessPromptPackRoot);
        assertEquals("/legacy/harness/skills",
                properties.harnessPlatformSkillRoot);
        assertEquals("/legacy/harness/mcp-servers",
                properties.harnessMcpServerRoot);
    }

    @Test
    void publicOverridesShouldUseTheirDedicatedEnvironmentVariables() {
        BoundCatalogProperties properties = bindApplicationProperties(
                "AGENT_CAPABILITY_RULE_ROOT=/trusted/public/rules",
                "AGENT_CAPABILITY_SKILL_ROOT=/trusted/public/skills",
                "AGENT_CAPABILITY_MCP_ROOT=/trusted/public/mcp-servers",
                "AGENT_HARNESS_PROMPT_PACK_ROOT=/legacy/harness/rules",
                "AGENT_HARNESS_PLATFORM_SKILL_ROOT=/legacy/harness/skills",
                "AGENT_HARNESS_MCP_SERVER_ROOT=/legacy/harness/mcp-servers");

        assertEquals("/trusted/public/rules", properties.capabilityRuleRoot);
        assertEquals("/trusted/public/skills", properties.capabilitySkillRoot);
        assertEquals("/trusted/public/mcp-servers", properties.capabilityMcpRoot);
        assertEquals("/legacy/harness/rules",
                properties.harnessPromptPackRoot);
        assertEquals("/legacy/harness/skills",
                properties.harnessPlatformSkillRoot);
        assertEquals("/legacy/harness/mcp-servers",
                properties.harnessMcpServerRoot);
    }

    private BoundCatalogProperties bindApplicationProperties(
            String... systemProperties) {
        return TestPropertyValues.of(systemProperties).applyToSystemProperties(
                () -> {
                    ConfigurableEnvironment environment =
                            new StandardEnvironment();
                    List<PropertySource<?>> applicationSources =
                            new YamlPropertySourceLoader().load(
                                    "application",
                                    new FileSystemResource(
                                            "src/main/resources/application.yml"));
                    for (PropertySource<?> source : applicationSources) {
                        environment.getPropertySources().addLast(source);
                    }
                    return new BoundCatalogProperties(
                            environment.getRequiredProperty(
                                    "agent.capability.rule-root"),
                            environment.getRequiredProperty(
                                    "agent.capability.platform-skill-root"),
                            environment.getRequiredProperty(
                                    "agent.capability.mcp-server-root"),
                            environment.getRequiredProperty(
                                    "agent.harness.prompt-pack-root"),
                            environment.getRequiredProperty(
                                    "agent.harness.platform-skill-root"),
                            environment.getRequiredProperty(
                                    "agent.harness.mcp-server-root"));
                });
    }

    private static final class BoundCatalogProperties {

        private final String capabilityRuleRoot;
        private final String capabilitySkillRoot;
        private final String capabilityMcpRoot;
        private final String harnessPromptPackRoot;
        private final String harnessPlatformSkillRoot;
        private final String harnessMcpServerRoot;

        private BoundCatalogProperties(
                String capabilityRuleRoot,
                String capabilitySkillRoot,
                String capabilityMcpRoot,
                String harnessPromptPackRoot,
                String harnessPlatformSkillRoot,
                String harnessMcpServerRoot) {
            this.capabilityRuleRoot = capabilityRuleRoot;
            this.capabilitySkillRoot = capabilitySkillRoot;
            this.capabilityMcpRoot = capabilityMcpRoot;
            this.harnessPromptPackRoot = harnessPromptPackRoot;
            this.harnessPlatformSkillRoot = harnessPlatformSkillRoot;
            this.harnessMcpServerRoot = harnessMcpServerRoot;
        }
    }
}
