package com.example.agentweb.config.workbench;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workbench 总开关与创建、写 Run 发布开关。
 *
 * @author alex
 * @since 2026-08-01
 */
@Component
@ConfigurationProperties(prefix = "agent.workbench")
@Getter
@Setter
public class WorkbenchReleaseProperties {

    private boolean enabled;
    private boolean createEnabled;
    private boolean writeRunEnabled;

    @PostConstruct
    public void validate() {
        if (!enabled && (createEnabled || writeRunEnabled)) {
            throw new IllegalStateException(
                    "Workbench subordinate release switches require "
                            + "agent.workbench.enabled");
        }
    }
}
