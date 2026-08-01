package com.example.agentweb.config.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Harness 退役窗口的创建、写入与导出分级开关。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.harness")
@Getter
@Setter
public class HarnessRetirementProperties {

    private boolean creationEnabled = true;
    private boolean mutationEnabled = true;
    private boolean exportEnabled = true;
}
