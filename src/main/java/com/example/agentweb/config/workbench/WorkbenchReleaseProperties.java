package com.example.agentweb.config.workbench;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Workbench 总开关、创建/写 Run 与高影响 Executor 发布开关。
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
    private HighImpact highImpact = new HighImpact();

    @PostConstruct
    public void validate() {
        if (highImpact == null) {
            throw new IllegalStateException(
                    "Invalid Workbench release configuration");
        }
        if (!enabled && (createEnabled || writeRunEnabled
                || highImpact.anyEnabled())) {
            throw new IllegalStateException(
                    "Workbench subordinate release switches require "
                            + "agent.workbench.enabled");
        }
    }

    /**
     * 类型化高影响 Executor 的独立发布开关。
     *
     * @author alex
     * @since 2026-08-01
     */
    @Getter
    @Setter
    public static class HighImpact {

        private boolean commitEnabled;
        private boolean pushEnabled;
        private boolean localDeployEnabled;
        private boolean productionWriteEnabled;

        private boolean anyEnabled() {
            return commitEnabled || pushEnabled
                    || localDeployEnabled || productionWriteEnabled;
        }
    }
}
