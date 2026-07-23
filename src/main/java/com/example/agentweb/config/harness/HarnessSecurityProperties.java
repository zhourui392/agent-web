package com.example.agentweb.config.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Harness 能力策略、环境 MCP allowlist 与最小进程环境配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConfigurationProperties(prefix = "agent.harness.security")
@Getter
@Setter
public class HarnessSecurityProperties {

    private String capabilityPolicyVersion = "harness-capability-policy@2.0.0";
    private String platformSafety = "遵守平台权限边界；未明确授权的文件、命令和外部能力默认拒绝。";
    private String environmentGuardrail = "只在 Run 声明的环境和工作目录内操作，不读取或输出敏感凭据。";
    private Set<String> allowedMcpServerIds = new LinkedHashSet<String>();
    private Set<String> inheritedEnvironmentVariables = new LinkedHashSet<String>();
}
