package com.example.agentweb.app.auth;

import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

/**
 * 管理后台用户读模型，不包含密码哈希。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
@AllArgsConstructor
public class AdminUserView {

    private final String id;
    private final String username;
    private final UserRole role;
    private final boolean enabled;
    private final Instant createdAt;
    private final Instant updatedAt;

    public static AdminUserView from(UserAccount account) {
        return new AdminUserView(
                account.getId(), account.getUsername(), account.getRole(), account.isEnabled(),
                account.getCreatedAt(), account.getUpdatedAt());
    }
}
