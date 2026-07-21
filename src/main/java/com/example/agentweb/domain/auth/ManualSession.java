package com.example.agentweb.domain.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * 本地登录会话聚合根。用户在 {@code /login.html} 输入工号 + 用户名后创建，
 * 浏览器通过本地 Cookie 持有会话标识。
 *
 * <p>不变量:userId / userName 非空白;ttlSeconds 为正;sessionId 由工厂用 256-bit
 * {@link SecureRandom} 生成 base64url 编码,32 字节熵远高于 UUID 的 122-bit,且不依赖
 * 浏览器侧。{@code create} 是聚合根唯一入口,禁止外部直接 new (反序列化 setter 留给 Repo 用)。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class ManualSession {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String sessionId;
    private final String userId;
    private final String userName;
    private final Instant createdAt;
    private final Instant expiresAt;

    private ManualSession(String sessionId, String userId, String userName,
                          Instant createdAt, Instant expiresAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userName = userName;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /**
     * 工厂:校验入参 → 生成 sessionId → 算 expiresAt。校验失败抛 {@link IllegalArgumentException}。
     */
    public static ManualSession create(String userId, String userName, long ttlSeconds, Clock clock) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (userName == null || userName.trim().isEmpty()) {
            throw new IllegalArgumentException("userName 不能为空");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds 必须 > 0");
        }
        Instant now = clock.instant();
        return new ManualSession(generateSessionId(), userId.trim(), userName.trim(),
                now, now.plusSeconds(ttlSeconds));
    }

    /**
     * 从持久化数据重建聚合根。仅 Repo 实现使用,不走入参校验 (假定库内数据来自合法 {@link #create})。
     */
    public static ManualSession restore(String sessionId, String userId, String userName,
                                        Instant createdAt, Instant expiresAt) {
        return new ManualSession(sessionId, userId, userName, createdAt, expiresAt);
    }

    private static String generateSessionId() {
        byte[] buf = new byte[TOKEN_BYTES];
        RNG.nextBytes(buf);
        return URL_ENCODER.encodeToString(buf);
    }

    public boolean isExpired(Clock clock) {
        return !clock.instant().isBefore(expiresAt);
    }

    public LoginUser toLoginUser() {
        return new LoginUser(userId, userName, null);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
