package com.example.agentweb.domain.auth;

import java.time.Clock;
import java.util.Optional;

/**
 * 本地登录会话认证领域服务，统一处理会话查询、过期判断和失效数据清理。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class ManualSessionAuthenticator {

    private final ManualSessionRepository repository;
    private final UserAccountRepository userAccountRepository;
    private final Clock clock;

    public ManualSessionAuthenticator(ManualSessionRepository repository,
                                      UserAccountRepository userAccountRepository,
                                      Clock clock) {
        this.repository = repository;
        this.userAccountRepository = userAccountRepository;
        this.clock = clock;
    }

    /**
     * 认证本地会话令牌。
     *
     * @param sessionToken 本地会话令牌
     * @return 有效会话对应的登录用户，无效或过期时返回空
     */
    public Optional<LoginUser> authenticate(String sessionToken) {
        if (sessionToken == null || sessionToken.isEmpty()) {
            return Optional.empty();
        }
        ManualSession session = repository.findById(sessionToken).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(clock)) {
            repository.deleteById(sessionToken);
            return Optional.empty();
        }
        UserAccount account = userAccountRepository.findById(session.getUserId()).orElse(null);
        if (account == null || !account.isEnabled()) {
            repository.deleteById(sessionToken);
            return Optional.empty();
        }
        return Optional.of(session.toLoginUser(account));
    }
}
