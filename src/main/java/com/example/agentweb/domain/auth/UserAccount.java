package com.example.agentweb.domain.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 用户账户聚合根，封装账户可用性、密码校验与角色语义。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public final class UserAccount {

    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_LENGTH = 256;

    private final String id;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant updatedAt;

    private UserAccount(String id, String username, String passwordHash, UserRole role,
                        boolean enabled, Instant createdAt, Instant updatedAt) {
        this.id = requireText(id, "id");
        this.username = requireText(username, "username");
        this.passwordHash = requireText(passwordHash, "passwordHash");
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        this.role = role;
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不能为空");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    /**
     * 从持久化状态重建账户，并校验持久化不变量。
     */
    public static UserAccount restore(String id, String username, String passwordHash, UserRole role,
                                      boolean enabled, Instant createdAt, Instant updatedAt) {
        return new UserAccount(id, username, passwordHash, role, enabled, createdAt, updatedAt);
    }

    /**
     * 创建默认启用的账户，集中维护用户名、密码和角色不变量。
     */
    public static UserAccount create(String username, String rawPassword, UserRole role,
                                     PasswordHasher hasher, Instant createdAt) {
        Objects.requireNonNull(hasher, "hasher 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        String normalizedUsername = requireText(username, "username");
        if (normalizedUsername.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("用户名不能超过 64 个字符");
        }
        validateRawPassword(rawPassword);
        return new UserAccount(
                UUID.randomUUID().toString(), normalizedUsername, hasher.encode(rawPassword),
                role, true, createdAt, createdAt);
    }

    /**
     * 校验当前账户是否允许使用该密码登录。
     */
    public boolean authenticate(String rawPassword, PasswordVerifier verifier) {
        Objects.requireNonNull(verifier, "verifier 不能为空");
        return enabled && rawPassword != null && verifier.matches(rawPassword, passwordHash);
    }

    /**
     * 修改账户密码，返回包含新密码哈希的账户状态。
     */
    public UserAccount changePassword(String rawPassword, PasswordHasher hasher, Instant changedAt) {
        Objects.requireNonNull(hasher, "hasher 不能为空");
        Objects.requireNonNull(changedAt, "changedAt 不能为空");
        validateRawPassword(rawPassword);
        return new UserAccount(id, username, hasher.encode(rawPassword), role, enabled, createdAt, changedAt);
    }

    private static void validateRawPassword(String rawPassword) {
        if (rawPassword == null
                || rawPassword.trim().isEmpty()
                || rawPassword.length() < MIN_PASSWORD_LENGTH
                || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码长度必须在 12 到 256 个字符之间");
        }
    }

    public boolean isAdmin() {
        return UserRole.ADMIN == role;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value.trim();
    }

    public String getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
