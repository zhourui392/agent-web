package com.example.agentweb.domain.auth;

import java.time.Clock;

/**
 * 用户密码领域服务，编排账户状态持久化与既有会话失效。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class UserPasswordService {

    private final UserAccountRepository userAccountRepository;
    private final ManualSessionRepository sessionRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public UserPasswordService(UserAccountRepository userAccountRepository,
                               ManualSessionRepository sessionRepository,
                               PasswordHasher passwordHasher,
                               Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.sessionRepository = sessionRepository;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
    }

    /**
     * 保存新密码哈希，并注销该账户此前签发的全部会话。
     */
    public void changePassword(UserAccount account, String rawPassword) {
        UserAccount changed = account.changePassword(rawPassword, passwordHasher, clock.instant());
        userAccountRepository.save(changed);
        sessionRepository.deleteByUserId(changed.getId());
    }
}
