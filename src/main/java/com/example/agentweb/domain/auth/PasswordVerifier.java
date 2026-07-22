package com.example.agentweb.domain.auth;

/**
 * 密码校验端口。领域层只表达校验语义，不依赖具体哈希算法。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@FunctionalInterface
public interface PasswordVerifier {

    /**
     * @param rawPassword 用户提交的明文密码
     * @param encodedPassword 持久化的单向哈希
     * @return 是否匹配
     */
    boolean matches(String rawPassword, String encodedPassword);
}
