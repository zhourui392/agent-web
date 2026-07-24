package com.example.agentweb.config.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Harness local 部署进程的超时与输出边界。
 *
 * @author alex
 * @since 2026-07-23
 */
@Component
@ConfigurationProperties(prefix = "agent.harness.deployment")
@Getter
@Setter
public class HarnessDeploymentProperties {

    private long commandTimeoutSeconds = 600L;
    private long maxOutputBytes = 1024L * 1024L;
}
