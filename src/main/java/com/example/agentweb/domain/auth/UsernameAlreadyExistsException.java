package com.example.agentweb.domain.auth;

/**
 * 用户名违反大小写不敏感的唯一性约束。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class UsernameAlreadyExistsException extends RuntimeException {

    public UsernameAlreadyExistsException() {
        super("用户名已存在");
    }

    public UsernameAlreadyExistsException(Throwable cause) {
        super("用户名已存在", cause);
    }
}
