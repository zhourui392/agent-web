package com.example.agentweb.app.git;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 注入 agent CLI 子进程的 git 环境规格：提交身份 env + （可选）解密后的 push 凭证。
 *
 * <p>{@code credentialPassword} 是<b>解密后明文</b>，仅用于即时写入子进程 env，绝不落盘 / 不持久。
 * 系统默认用户 / 未配置用户返回 {@link #EMPTY}（不注入任何变量 → 子进程继承机器默认 git）。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public final class GitEnvSpec {

    /** 空规格：不注入任何 git 变量。 */
    public static final GitEnvSpec EMPTY =
            new GitEnvSpec(Collections.<String, String>emptyMap(), null, null);

    private final Map<String, String> identityEnv;
    private final String credentialUsername;
    private final String credentialPassword;

    public GitEnvSpec(Map<String, String> identityEnv, String credentialUsername, String credentialPassword) {
        this.identityEnv = identityEnv == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(identityEnv));
        this.credentialUsername = credentialUsername;
        this.credentialPassword = credentialPassword;
    }

    /** 提交身份 env（{@code GIT_AUTHOR_*}/{@code GIT_COMMITTER_*}），空规格返回空 map。 */
    public Map<String, String> getIdentityEnv() {
        return identityEnv;
    }

    public String getCredentialUsername() {
        return credentialUsername;
    }

    public String getCredentialPassword() {
        return credentialPassword;
    }

    /** 是否无任何可注入内容。 */
    public boolean isEmpty() {
        return identityEnv.isEmpty() && !hasCredential();
    }

    /** 是否携带可用的 push 凭证（用户名 + 解密密码均就绪）。 */
    public boolean hasCredential() {
        return credentialUsername != null && !credentialUsername.isEmpty()
                && credentialPassword != null && !credentialPassword.isEmpty();
    }
}
