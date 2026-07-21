package com.example.agentweb.infra.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理后台独立口令鉴权:校验口令、签发/校验内存会话令牌。
 *
 * <p>令牌存内存,进程重启后失效(内部工具可接受,重启后重新登录即可)。口令比对走
 * {@link MessageDigest#isEqual} 常量时间比较,避免计时侧信道。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@Service
public class AdminAccessService {

    private static final int TOKEN_BYTES = 32;
    private static final long MILLIS_PER_MINUTE = 60_000L;

    private final AdminProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** token → 过期时间(epoch millis)。 */
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();

    @Autowired
    public AdminAccessService(AdminProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** 测试用:可注入固定/可推进时钟以验证令牌过期。 */
    AdminAccessService(AdminProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 校验口令,通过则签发会话令牌。
     *
     * @param password 用户提交的明文口令
     * @return 通过返回令牌;口令未配置(管理台禁用)或不匹配返回空
     */
    public Optional<String> login(String password) {
        if (!passwordMatches(password)) {
            return Optional.empty();
        }
        String token = newToken();
        sessions.put(token, clock.millis() + properties.getSessionTtlMinutes() * MILLIS_PER_MINUTE);
        return Optional.of(token);
    }

    /**
     * 令牌是否有效(存在且未过期)。过期令牌顺带剔除。
     *
     * @param token 会话令牌,可空
     * @return 有效返回 {@code true}
     */
    public boolean isAuthenticated(String token) {
        if (token == null) {
            return false;
        }
        Long expiresAt = sessions.get(token);
        if (expiresAt == null) {
            return false;
        }
        if (clock.millis() >= expiresAt) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    /**
     * 注销令牌。
     *
     * @param token 会话令牌,可空
     */
    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 常量时间比对;口令未配置或输入为空时直接拒绝,杜绝空口令登录。 */
    private boolean passwordMatches(String input) {
        String configured = properties.getPassword();
        if (configured == null || configured.isEmpty() || input == null) {
            return false;
        }
        byte[] expected = configured.getBytes(StandardCharsets.UTF_8);
        byte[] actual = input.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private String newToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
