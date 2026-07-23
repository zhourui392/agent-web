package com.example.agentweb.config.harness;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Harness 专用 Codex Runtime 的进程、超时、输出和临时目录配置。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Component
@ConfigurationProperties(prefix = "agent.harness.runtime")
@Getter
@Setter
public class HarnessRuntimeProperties {

    private String codexCommand = "codex";
    private String tempRoot = "data/harness/runtime";
    private String sandboxMode = "read-only";
    private Set<String> supportedCodexVersions = new LinkedHashSet<String>(
            Collections.singleton("0.145.0"));
    private String compatibilityMatrixVersion = "m0-2026-07-22";
    private long versionProbeTimeoutSeconds = 5L;
    private long versionProbeMaxBytes = 4096L;
    private long idleTimeoutSeconds = 300L;
    private long maxRuntimeSeconds = 1800L;
    private long maxOutputBytes = 4L * 1024L * 1024L;
}
