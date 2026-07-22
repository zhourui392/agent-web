package com.example.agentweb.domain.auth;

import java.time.Clock;
import java.util.Objects;

/**
 * 用户注册领域服务，维护跨账户的用户名唯一性并持久化新聚合。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class UserRegistrationService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordHasher passwordHasher;
    private final Clock clock;

    public UserRegistrationService(UserAccountRepository userAccountRepository,
                                   PasswordHasher passwordHasher,
                                   Clock clock) {
        this.userAccountRepository = Objects.requireNonNull(
                userAccountRepository, "userAccountRepository 不能为空");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
    }

    /**
     * 注册一个新账户；用户名按数据库契约做大小写不敏感判重。
     */
    public UserAccount register(String username, String rawPassword, UserRole role) {
        if (userAccountRepository.findByUsername(username).isPresent()) {
            throw new UsernameAlreadyExistsException();
        }
        UserAccount account = UserAccount.create(
                username, rawPassword, role, passwordHasher, clock.instant());
        userAccountRepository.save(account);
        return account;
    }
}
