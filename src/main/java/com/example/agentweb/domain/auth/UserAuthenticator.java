package com.example.agentweb.domain.auth;

import java.util.Optional;

/**
 * 用户名密码认证领域服务。
 *
 * <p>未命中用户时仍执行一次密码哈希校验，减小通过响应时延枚举用户的差异。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class UserAuthenticator {

    private final UserAccountRepository repository;
    private final PasswordVerifier passwordVerifier;
    private final String dummyPasswordHash;

    public UserAuthenticator(UserAccountRepository repository, PasswordVerifier passwordVerifier,
                             String dummyPasswordHash) {
        this.repository = repository;
        this.passwordVerifier = passwordVerifier;
        this.dummyPasswordHash = dummyPasswordHash;
    }

    public Optional<UserAccount> authenticate(String username, String rawPassword) {
        String normalizedUsername = username == null ? "" : username.trim();
        UserAccount account = repository.findByUsername(normalizedUsername).orElse(null);
        if (account == null) {
            passwordVerifier.matches(rawPassword, dummyPasswordHash);
            return Optional.empty();
        }
        return account.authenticate(rawPassword, passwordVerifier) ? Optional.of(account) : Optional.empty();
    }
}
