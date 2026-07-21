package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManualSession} 聚合根单测：工厂不变量、过期判定、转 {@link LoginUser}。
 *
 * @author zhourui(V33215020)
 */
class ManualSessionTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");
    private final Clock fixed = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void create_should_set_expiresAt_by_ttl() {
        ManualSession s = ManualSession.create("V33215020", "周锐", 604800, fixed);

        assertNotNull(s.getSessionId());
        assertEquals("V33215020", s.getUserId());
        assertEquals("周锐", s.getUserName());
        assertEquals(NOW, s.getCreatedAt());
        assertEquals(NOW.plusSeconds(604800), s.getExpiresAt());
    }

    @Test
    void create_should_generate_unique_sessionId() {
        ManualSession a = ManualSession.create("u1", "n1", 60, fixed);
        ManualSession b = ManualSession.create("u1", "n1", 60, fixed);

        assertFalse(a.getSessionId().equals(b.getSessionId()));
    }

    @Test
    void create_should_reject_blank_userId() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("", "name", 60, fixed));
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create(null, "name", 60, fixed));
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("  ", "name", 60, fixed));
    }

    @Test
    void create_should_reject_blank_userName() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("uid", "", 60, fixed));
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("uid", null, 60, fixed));
    }

    @Test
    void create_should_reject_non_positive_ttl() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("uid", "name", 0, fixed));
        assertThrows(IllegalArgumentException.class,
                () -> ManualSession.create("uid", "name", -1, fixed));
    }

    @Test
    void isExpired_should_be_false_before_expiry() {
        ManualSession s = ManualSession.create("uid", "name", 60, fixed);
        Clock beforeExpiry = Clock.fixed(NOW.plusSeconds(59), ZoneOffset.UTC);

        assertFalse(s.isExpired(beforeExpiry));
    }

    @Test
    void isExpired_should_be_true_at_or_after_expiry() {
        ManualSession s = ManualSession.create("uid", "name", 60, fixed);
        Clock atExpiry = Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC);
        Clock afterExpiry = Clock.fixed(NOW.plusSeconds(61), ZoneOffset.UTC);

        assertTrue(s.isExpired(atExpiry));
        assertTrue(s.isExpired(afterExpiry));
    }

    @Test
    void toLoginUser_should_map_id_and_name() {
        ManualSession s = ManualSession.create("V33215020", "周锐", 60, fixed);

        LoginUser u = s.toLoginUser();
        assertEquals("V33215020", u.getUserId());
        assertEquals("周锐", u.getUserName());
    }
}
