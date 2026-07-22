package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * BCrypt 密码哈希适配器，强度 12，每次编码使用独立随机盐。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class BCryptPasswordHasher implements PasswordHasher {

    private static final int STRENGTH = 12;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(STRENGTH);

    @Override
    public String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return rawPassword != null && encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }
}
