package com.example.agentweb.app.git;

import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 按会话 owner userId 产出注入 agent CLI 子进程的 git 环境规格。供 spawn 点统一调用。
 *
 * <p>无登录上下文 / 未配置 → {@link GitEnvSpec#EMPTY}（不注入 → 机器默认 git）。配置用户 →
 * {@code GIT_AUTHOR_*} / {@code GIT_COMMITTER_*}；凭证已配置且 cipher 启用时附带解密后的 push 凭证。
 * 编排层，不含业务规则——系统上下文判定下沉到 {@link GitConfigPolicy}。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Component
@Slf4j
public class GitEnvResolver {

    private final UserGitConfigRepository repository;
    private final GitConfigPolicy policy;
    private final GitCredentialCipher cipher;

    public GitEnvResolver(UserGitConfigRepository repository,
                          GitConfigPolicy policy,
                          GitCredentialCipher cipher) {
        this.repository = repository;
        this.policy = policy;
        this.cipher = cipher;
    }

    /**
     * 解析某用户的注入规格。
     *
     * @param userId 会话 owner 工号；{@code null}（系统路径）一律不注入
     * @return 注入规格，无登录上下文 / 未配置 → {@link GitEnvSpec#EMPTY}
     */
    public GitEnvSpec resolve(String userId) {
        if (policy.isSystemContext(userId)) {
            return GitEnvSpec.EMPTY;
        }
        UserGitConfig config = repository.findByUserId(userId).orElse(null);
        if (config == null || config.getIdentity() == null) {
            return GitEnvSpec.EMPTY;
        }
        Map<String, String> env = identityEnv(config.getIdentity());
        String credentialUsername = null;
        String credentialPassword = null;
        if (config.hasCredential() && cipher.isEnabled()) {
            String decrypted = tryDecrypt(userId, config.getCredentialPasswordCipher());
            if (decrypted != null) {
                credentialUsername = config.getCredentialUsername();
                credentialPassword = decrypted;
            }
        }
        return new GitEnvSpec(env, credentialUsername, credentialPassword);
    }

    private Map<String, String> identityEnv(GitIdentity identity) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GIT_AUTHOR_NAME", identity.getName());
        env.put("GIT_AUTHOR_EMAIL", identity.getEmail());
        env.put("GIT_COMMITTER_NAME", identity.getName());
        env.put("GIT_COMMITTER_EMAIL", identity.getEmail());
        return env;
    }

    /** 解密失败不应中断身份注入：降级为「无凭证」并告警。 */
    private String tryDecrypt(String userId, String cipherText) {
        try {
            return cipher.decrypt(cipherText);
        } catch (RuntimeException e) {
            log.warn("git-env-credential-decrypt-failed userId={} reason={}", userId, e.getMessage());
            return null;
        }
    }
}
