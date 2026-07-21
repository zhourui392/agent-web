package com.example.agentweb.domain.auth;

import java.time.Clock;

/**
 * 本地登录会话工厂，封装统一的会话有效期和时钟。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class ManualSessionFactory {

    private final long sessionTtlSeconds;
    private final Clock clock;

    public ManualSessionFactory(long sessionTtlSeconds, Clock clock) {
        this.sessionTtlSeconds = sessionTtlSeconds;
        this.clock = clock;
    }

    /**
     * 创建本地登录会话。
     *
     * @param employeeId 工号
     * @param userName 用户名
     * @return 新会话
     */
    public ManualSession create(String employeeId, String userName) {
        return ManualSession.create(employeeId, userName, sessionTtlSeconds, clock);
    }
}
