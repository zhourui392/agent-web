package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理后台口令鉴权核心逻辑单测：口令校验、令牌签发/校验/过期/注销。纯逻辑无 Mock。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAccessServiceTest {

    @Test
    public void login_correctPassword_returnsNonEmptyToken() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));

        Optional<String> token = svc.login("s3cret");

        assertTrue(token.isPresent());
        assertFalse(token.get().isEmpty());
    }

    @Test
    public void login_wrongPassword_returnsEmpty() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));

        assertFalse(svc.login("wrong").isPresent());
    }

    @Test
    public void login_nullOrEmptyInput_returnsEmpty() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));

        assertFalse(svc.login(null).isPresent());
        assertFalse(svc.login("").isPresent());
    }

    @Test
    public void login_whenPasswordNotConfigured_alwaysRejected() {
        // 未配置口令(空)= 管理台禁用,任何输入(含空)都拒绝,杜绝空口令登录绕过
        AdminAccessService svc = new AdminAccessService(props("", 480L));

        assertFalse(svc.login("").isPresent());
        assertFalse(svc.login("anything").isPresent());
    }

    @Test
    public void isAuthenticated_validTokenAfterLogin_true() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));

        String token = svc.login("s3cret").get();

        assertTrue(svc.isAuthenticated(token));
    }

    @Test
    public void isAuthenticated_unknownOrNullToken_false() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));

        assertFalse(svc.isAuthenticated("not-a-real-token"));
        assertFalse(svc.isAuthenticated(null));
    }

    @Test
    public void logout_invalidatesToken() {
        AdminAccessService svc = new AdminAccessService(props("s3cret", 480L));
        String token = svc.login("s3cret").get();

        svc.logout(token);

        assertFalse(svc.isAuthenticated(token));
    }

    @Test
    public void isAuthenticated_expiredToken_false() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-07T00:00:00Z"));
        AdminAccessService svc = new AdminAccessService(props("s3cret", 30L), clock);
        String token = svc.login("s3cret").get();
        assertTrue(svc.isAuthenticated(token));

        clock.advanceMinutes(31L);

        assertFalse(svc.isAuthenticated(token));
    }

    private AdminProperties props(String password, long ttlMinutes) {
        AdminProperties p = new AdminProperties();
        p.setPassword(password);
        p.setSessionTtlMinutes(ttlMinutes);
        return p;
    }

    /** 可手动推进的测试时钟,用于验证令牌过期。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advanceMinutes(long minutes) {
            this.now = this.now.plusSeconds(minutes * 60L);
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
            return now;
        }
    }
}
