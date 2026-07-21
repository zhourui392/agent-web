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
        /** Max execution time in seconds. */
        private int timeoutSeconds = 120;
        /** Grace period to drain stdout after process exit before assuming an inherited pipe is held. */
        private long stdoutDrainGraceMs = 3000L;

        // ── Codex 专属字段；Claude 方言不读取 ──
        /** Codex --model 参数；为空则不下发该 flag */
        private String model;
        /** Codex --dangerously-bypass-approvals-and-sandbox 开关，默认开 */
        private boolean sandboxBypass = true;
        /** Codex --skip-git-repo-check 开关，默认开 */
        private boolean skipGitCheck = true;
        /** Codex 透传到 codex exec 的额外参数，紧贴 stdin sentinel '-' 之前 */
        private List<String> extraArgs = new ArrayList<String>();

    }
}
