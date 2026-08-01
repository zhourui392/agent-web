package com.example.agentweb.config.runtime;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Chat/Workbench 公共进程 Runtime 的 feature flag、安全边界和受管技术参数。
 *
 * @author alex
 * @since 2026-08-01
 */
@ConfigurationProperties(prefix = "agent.runtime")
@Getter
@Setter
public class CommonRuntimeProperties {

    private boolean chatEnabled;
    private boolean workbenchEnabled;
    private String codexCommand = "codex";
    private String tempRoot = "data/runtime";
    private String credentialEnvironmentReference = "";
    private Set<String> supportedCodexVersions =
            new LinkedHashSet<String>(Collections.singleton("0.145.0"));
    private String compatibilityMatrixVersion = "m0-2026-07-22";
    private long versionProbeTimeoutSeconds = 5L;
    private long versionProbeMaxBytes = 4096L;
    private long chatTimeoutSeconds = 7200L;
    private long chatMaxOutputBytes = 8L * 1024L * 1024L;

    @PostConstruct
    public void validate() {
        if (isBlank(codexCommand)
                || isBlank(tempRoot)
                || supportedCodexVersions == null
                || supportedCodexVersions.isEmpty()
                || supportedCodexVersions.contains(null)
                || isBlank(compatibilityMatrixVersion)
                || versionProbeTimeoutSeconds < 1L
                || versionProbeMaxBytes < 1L
                || chatTimeoutSeconds < 1L
                || chatMaxOutputBytes < 1L) {
            throw new IllegalStateException(
                    "Invalid common Runtime configuration");
        }
        if ((chatEnabled || workbenchEnabled)
                && isBlank(credentialEnvironmentReference)) {
            throw new IllegalStateException(
                    "Enabled common Runtime requires an explicit "
                            + "credential environment reference");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
