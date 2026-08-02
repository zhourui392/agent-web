package com.example.agentweb.config;

import com.example.agentweb.config.capability.CapabilityCatalogProperties;
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
 * 公共 Capability Catalog 配置的默认值及环境变量隔离测试。
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

        assertEquals(COMMON_RULE_ROOT, capability.getRuleRoot());
        assertEquals(COMMON_SKILL_ROOT, capability.getPlatformSkillRoot());
        assertEquals(COMMON_MCP_ROOT, capability.getMcpServerRoot());
    }

    @Test
    void publicOverridesShouldUseTheirDedicatedEnvironmentVariables() {
        BoundCatalogProperties properties = bindApplicationProperties(
                "AGENT_CAPABILITY_RULE_ROOT=/trusted/public/rules",
                "AGENT_CAPABILITY_SKILL_ROOT=/trusted/public/skills",
                "AGENT_CAPABILITY_MCP_ROOT=/trusted/public/mcp-servers");

        assertEquals("/trusted/public/rules", properties.capabilityRuleRoot);
        assertEquals("/trusted/public/skills", properties.capabilitySkillRoot);
        assertEquals("/trusted/public/mcp-servers", properties.capabilityMcpRoot);
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
                                    "agent.capability.mcp-server-root"));
                });
    }

    private static final class BoundCatalogProperties {

        private final String capabilityRuleRoot;
        private final String capabilitySkillRoot;
        private final String capabilityMcpRoot;

        private BoundCatalogProperties(
                String capabilityRuleRoot,
                String capabilitySkillRoot,
                String capabilityMcpRoot) {
            this.capabilityRuleRoot = capabilityRuleRoot;
            this.capabilitySkillRoot = capabilitySkillRoot;
            this.capabilityMcpRoot = capabilityMcpRoot;
        }
    }
}
