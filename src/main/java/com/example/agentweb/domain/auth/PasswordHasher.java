package com.example.agentweb.domain.auth;

/**
 * 密码单向哈希端口，用于初始化或修改密码。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface PasswordHasher extends PasswordVerifier {

    /**
     * @param rawPassword 明文密码
     * @return 含随机盐的单向哈希
     */
    String encode(String rawPassword);
}
