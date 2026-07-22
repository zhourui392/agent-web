package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LoginAttemptLimiter} 登录失败窗口测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class LoginAttemptLimiterTest {

    @Test
    void shouldBlockIpAndAccountAfterConfiguredFailures_thenExpireWithWindow() {
        AuthProperties properties = properties(2, 60);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        LoginAttemptLimiter limiter = LoginAttemptLimiter.forTesting(properties, clock);

        limiter.recordFailure("192.0.2.1", "Admin");
        assertEquals(0L, limiter.retryAfterSeconds("192.0.2.1", "admin"));
        limiter.recordFailure("192.0.2.1", "admin");

        assertEquals(60L, limiter.retryAfterSeconds("192.0.2.1", "someone-else"));
        assertEquals(60L, limiter.retryAfterSeconds("198.51.100.1", "ADMIN"));

        clock.instant = clock.instant.plusSeconds(61);
        assertEquals(0L, limiter.retryAfterSeconds("192.0.2.1", "admin"));
    }

    @Test
    void shouldClearAccountFailuresAfterSuccess_withoutClearingSourceIpFailures() {
        AuthProperties properties = properties(2, 60);
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        LoginAttemptLimiter limiter = LoginAttemptLimiter.forTesting(properties, clock);

        limiter.recordFailure("192.0.2.1", "admin");
        limiter.recordSuccess("198.51.100.1", "ADMIN");

        assertEquals(0L, limiter.retryAfterSeconds("198.51.100.1", "admin"));
        limiter.recordFailure("192.0.2.1", "another-account");
        assertEquals(60L, limiter.retryAfterSeconds("192.0.2.1", "admin"));
    }

    private AuthProperties properties(int maxFailures, long windowSeconds) {
        AuthProperties properties = new AuthProperties();
        properties.setLoginMaxFailures(maxFailures);
        properties.setLoginFailureWindowSeconds(windowSeconds);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
