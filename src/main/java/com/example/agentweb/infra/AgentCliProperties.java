package com.example.agentweb.infra;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for CLI agents.
 * Users can override by application.yml or environment variables.
 * @author zhourui(V33215020)
 */
@Component
@ConfigurationProperties(prefix = "agent.cli")
@Getter
public class AgentCliProperties {

    private final Client codex = new Client();
    private final Client claude = new Client();

    @Getter
    @Setter
    public static class Client {
        /** Executable path, e.g. "codex" or "/usr/local/bin/claude" */
        private String exec;
        /** Arguments template; use ${MESSAGE} placeholder for user message if needed.
         *  当为空时，方言可按内置规则拼装命令（例如 CodexCliDialect 会走真实 codex exec 路径）。 */
        private List<String> args = new ArrayList<String>();
        /** If true, write user message to stdin of the process. */
        private boolean stdin = true;
        /** 同步 runOnce 的硬执行上限（秒）；流式运行使用下方两个独立期限。 */
        private int timeoutSeconds = 120;
        /** 流式运行 stdout 无活动多久后终止；小于等于 0 表示禁用空闲期限。 */
        private long streamIdleTimeoutSeconds;
        /** 流式运行的绝对时长上限，不会被 stdout 活动续期；小于等于 0 表示禁用。 */
        private long streamMaxRuntimeSeconds;
        /** Grace period to drain stdout after process exit before assuming an inherited pipe is held. */
        private long stdoutDrainGraceMs = 3000L;
        /** 单次运行最多接收的 stdout 字节数，防止无界内存与持久化增长。 */
        private long maxOutputBytes = 10L * 1024L * 1024L;

        // ── Codex 专属字段；Claude 方言不读取 ──
        /** Codex --model 参数；为空则不下发该 flag */
        private String model;
        /** Codex --dangerously-bypass-approvals-and-sandbox 开关，默认关闭。 */
        private boolean sandboxBypass = false;
        /** Codex --skip-git-repo-check 开关，默认开 */
        private boolean skipGitCheck = true;
        /** Codex 透传到 codex exec 的额外参数，紧贴 stdin sentinel '-' 之前 */
        private List<String> extraArgs = new ArrayList<String>();

    }
}
