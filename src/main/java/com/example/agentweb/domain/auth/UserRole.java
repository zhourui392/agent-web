package com.example.agentweb.domain.auth;

/**
 * 用户角色。管理端授权只依赖该显式角色，不依赖用户名等隐式约定。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public enum UserRole {
    USER,
    ADMIN
}
