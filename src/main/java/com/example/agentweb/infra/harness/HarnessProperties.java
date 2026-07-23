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
}
