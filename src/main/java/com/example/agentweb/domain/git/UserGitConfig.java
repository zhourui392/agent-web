package com.example.agentweb.domain.git;

import java.time.Instant;

/**
 * 单个用户的 git 配置聚合根：提交身份 {@link GitIdentity} + push 凭证引用。
 *
 * <p>凭证以「用户名(明文,非敏感) + 密码密文(不可逆 opaque)」形态持有，聚合根<b>不持明文密码</b>、
 * 不负责加解密——加解密属 infra 关注点。聚合 persistence-ignorant：不注入/调用 Repository。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class UserGitConfig {

    private final String userId;
    private GitIdentity identity;
    private String credentialUsername;
    private String credentialPasswordCipher;
    private Instant updatedAt;

    private UserGitConfig(String userId, GitIdentity identity, String credentialUsername,
                          String credentialPasswordCipher, Instant updatedAt) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        this.userId = userId.trim();
        this.identity = identity;
        this.credentialUsername = credentialUsername;
        this.credentialPasswordCipher = credentialPasswordCipher;
        this.updatedAt = updatedAt;
    }

    /**
     * 新建配置，身份必填（无身份的配置无意义，也无可注入内容）。
     *
     * @param userId   用户工号
     * @param identity 提交身份
     * @param now      变更时间戳
     * @return 新聚合
     */
    public static UserGitConfig create(String userId, GitIdentity identity, Instant now) {
        if (identity == null) {
            throw new IllegalArgumentException("identity is required to create UserGitConfig");
        }
        return new UserGitConfig(userId, identity, null, null, now);
    }

    /**
     * 从持久化字段重建聚合（供 Repository 使用），不触发构造期身份校验之外的业务校验。
     *
     * @param userId                   用户工号
     * @param identity                 提交身份（可为 null，老数据兜底）
     * @param credentialUsername       push 用户名明文（可为 null）
     * @param credentialPasswordCipher push 密码密文（可为 null）
     * @param updatedAt                上次更新时间
     * @return 重建的聚合
     */
    public static UserGitConfig restore(String userId, GitIdentity identity, String credentialUsername,
                                        String credentialPasswordCipher, Instant updatedAt) {
        return new UserGitConfig(userId, identity, credentialUsername, credentialPasswordCipher, updatedAt);
    }

    /**
     * 更新提交身份。
     *
     * @param newIdentity 新身份，非空
     * @param now         变更时间戳
     */
    public void updateIdentity(GitIdentity newIdentity, Instant now) {
        if (newIdentity == null) {
            throw new IllegalArgumentException("identity is required");
        }
        this.identity = newIdentity;
        this.updatedAt = now;
    }

    /**
     * 设置 / 更新 push 凭证。
     *
     * @param username       push 用户名明文，非空
     * @param passwordCipher push 密码密文，非空（明文绝不进聚合）
     * @param now            变更时间戳
     */
    public void updateCredential(String username, String passwordCipher, Instant now) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("credential username must not be blank");
        }
        if (passwordCipher == null || passwordCipher.trim().isEmpty()) {
            throw new IllegalArgumentException("credential cipher must not be blank");
        }
        this.credentialUsername = username.trim();
        this.credentialPasswordCipher = passwordCipher;
        this.updatedAt = now;
    }

    /**
     * 清除 push 凭证（回落到机器默认凭证助手）。
     *
     * @param now 变更时间戳
     */
    public void clearCredential(Instant now) {
        this.credentialUsername = null;
        this.credentialPasswordCipher = null;
        this.updatedAt = now;
    }

    /**
     * 是否已配置完整 push 凭证。
     *
     * @return 用户名与密文均非空时为 true
     */
    public boolean hasCredential() {
        return credentialUsername != null && !credentialUsername.isEmpty()
                && credentialPasswordCipher != null && !credentialPasswordCipher.isEmpty();
    }

    public String getUserId() {
        return userId;
    }

    public GitIdentity getIdentity() {
        return identity;
    }

    public String getCredentialUsername() {
        return credentialUsername;
    }

    public String getCredentialPasswordCipher() {
        return credentialPasswordCipher;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
