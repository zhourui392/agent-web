package com.example.agentweb.domain.auth;

import java.util.Optional;

/**
 * {@link UserAccount} 写侧仓储端口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface UserAccountRepository {

    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findById(String id);

    void save(UserAccount account);
}
