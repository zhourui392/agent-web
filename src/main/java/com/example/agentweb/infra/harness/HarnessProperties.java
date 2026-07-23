package com.example.agentweb.infra.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Harness Feature Flag 与受控 Artifact Root 配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConfigurationProperties(prefix = "agent.harness")
@Getter
@Setter
public class HarnessProperties {

    private boolean enabled;
    private String artifactRoot = "data/harness/artifacts";
    private String promptPackRoot = "src/main/resources/harness/prompt-packs";
    private String platformSkillRoot = "src/main/resources/harness/skills";
    private String approvedUserSkillRoot;
    private String workspaceSkillRoot;
    private String capabilityPolicyVersion = "harness-capability-policy@1.0.0";
    private String platformSafety = "遵守平台权限边界；未明确授权的文件、命令和外部能力默认拒绝。";
    private String environmentGuardrail = "只在 Run 声明的环境和工作目录内操作，不读取或输出敏感凭据。";
}
