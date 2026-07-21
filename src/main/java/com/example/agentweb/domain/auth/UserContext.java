package com.example.agentweb.domain.auth;

import java.util.Optional;

/**
 * 当前请求登录用户的读端口。把“当前请求是谁”从 ThreadLocal / Cookie 等具体存储里解耦出来，
 * 供领域服务（{@link CurrentUserProvider}）只读消费；写入由 infra 实现（请求过滤器）负责。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public interface UserContext {

    /**
     * 当前登录用户；非请求线程、未登录或无会话上下文时返回 {@link Optional#empty()}。
     *
     * @return 当前登录用户
     */
    Optional<LoginUser> currentUser();

    /**
     * 当前登录用户标识；拿不到返回 {@code null}。
     *
     * @return userId 或 {@code null}
     */
    default String currentUserId() {
        return currentUser().map(LoginUser::getUserId).orElse(null);
    }

    /**
     * 当前登录用户姓名；拿不到返回 {@code null}。
     *
     * @return userName 或 {@code null}
     */
    default String currentUserName() {
        return currentUser().map(LoginUser::getUserName).orElse(null);
    }
}
