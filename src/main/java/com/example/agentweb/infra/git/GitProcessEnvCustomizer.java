package com.example.agentweb.infra.git;

import com.example.agentweb.app.git.GitEnvResolver;
import com.example.agentweb.app.git.GitEnvSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * 在 agent CLI 子进程 spawn 前定制 {@link ProcessBuilder} 环境：注入 git 提交身份 +（可选）push 凭证。
 *
 * <p>系统默认用户 / 未配置 → 空注入（{@code apply} 直接返回，子进程继承机器默认 git）。
 * 在 spawn 点统一调用，不改任何磁盘 git config，可随时回落。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@Slf4j
public class GitProcessEnvCustomizer {

    private static final String ENV_ASKPASS = "GIT_ASKPASS";
    private static final String ENV_TERMINAL_PROMPT = "GIT_TERMINAL_PROMPT";
    private static final String ENV_CRED_USERNAME = "AGENT_GIT_USERNAME";
    private static final String ENV_CRED_PASSWORD = "AGENT_GIT_PASSWORD";

    private final GitEnvResolver resolver;
    private final GitAskpassScript askpassScript;

    public GitProcessEnvCustomizer(GitEnvResolver resolver, GitAskpassScript askpassScript) {
        this.resolver = resolver;
        this.askpassScript = askpassScript;
    }

    /**
     * 按 userId 注入 git 环境到给定子进程构造器。
     *
     * @param processBuilder 子进程构造器
     * @param userId         会话 owner 工号；系统路径传 null（不注入）
     */
    public void apply(ProcessBuilder processBuilder, String userId) {
        GitEnvSpec spec = resolver.resolve(userId);
        if (spec.isEmpty()) {
            return;
        }
        Map<String, String> env = processBuilder.environment();
        env.putAll(spec.getIdentityEnv());
        if (spec.hasCredential()) {
            applyCredential(env, spec);
        }
        log.debug("git-env-injected userId={} identity={} credential={}",
                userId, !spec.getIdentityEnv().isEmpty(), spec.hasCredential());
    }

    /**
     * 仅注入提交身份，不把 push 密码/token 暴露给可执行任意命令的 Agent 进程。
     */
    public void applyIdentityOnly(ProcessBuilder processBuilder, String userId) {
        GitEnvSpec spec = resolver.resolve(userId);
        processBuilder.environment().putAll(spec.getIdentityEnv());
        log.debug("git-identity-env-injected userId={} identity={}",
                userId, !spec.getIdentityEnv().isEmpty());
    }

    /** 写一次性 askpass 脚本并把凭证放进 env；脚本写失败仅告警，回落到无凭证（身份仍生效）。 */
    private void applyCredential(Map<String, String> env, GitEnvSpec spec) {
        try {
            env.put(ENV_ASKPASS, askpassScript.ensureScript());
            env.put(ENV_TERMINAL_PROMPT, "0");
            env.put(ENV_CRED_USERNAME, spec.getCredentialUsername());
            env.put(ENV_CRED_PASSWORD, spec.getCredentialPassword());
        } catch (IOException e) {
            log.warn("git-askpass-script-write-failed reason={}", e.getMessage());
        }
    }
}
