package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ManualSessionAuthenticator} 领域服务测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
class ManualSessionAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-06-08T00:00:00Z");

    @Test
    void should_ReturnUser_When_SessionIsValid() {
        // Given
        StubSessionRepository repository = new StubSessionRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ManualSession session = ManualSession.create("E10001", "张三", 60, clock);
        repository.save(session);
        ManualSessionAuthenticator authenticator = new ManualSessionAuthenticator(repository, clock);

        // When
        Optional<LoginUser> result = authenticator.authenticate(session.getSessionId());

        // Then
        assertTrue(result.isPresent());
        assertEquals("E10001", result.get().getUserId());
        assertEquals("张三", result.get().getUserName());
    }

    @Test
    void should_DeleteSession_When_SessionIsExpired() {
        // Given
        StubSessionRepository repository = new StubSessionRepository();
        Clock createdClock = Clock.fixed(NOW, ZoneOffset.UTC);
        ManualSession session = ManualSession.create("E10001", "张三", 60, createdClock);
        repository.save(session);
        Clock expiredClock = Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC);
        ManualSessionAuthenticator authenticator = new ManualSessionAuthenticator(repository, expiredClock);

        // When
        Optional<LoginUser> result = authenticator.authenticate(session.getSessionId());

        // Then
        assertFalse(result.isPresent());
        assertFalse(repository.sessions.containsKey(session.getSessionId()));
    }

    @Test
    void should_ReturnEmpty_When_TokenIsMissing() {
        // Given
        ManualSessionAuthenticator authenticator = new ManualSessionAuthenticator(
                new StubSessionRepository(), Clock.fixed(NOW, ZoneOffset.UTC));

        // When / Then
        assertFalse(authenticator.authenticate(null).isPresent());
        assertFalse(authenticator.authenticate("").isPresent());
    }

    private static final class StubSessionRepository implements ManualSessionRepository {
        private final Map<String, ManualSession> sessions = new HashMap<>();

        @Override
        public void save(ManualSession session) {
            sessions.put(session.getSessionId(), session);
        }

        @Override
        public Optional<ManualSession> findById(String sessionId) {
            return Optional.ofNullable(sessions.get(sessionId));
        }

        @Override
        public void deleteById(String sessionId) {
            sessions.remove(sessionId);
        }

        @Override
        public int deleteExpiredBefore(Instant threshold) {
            return 0;
        }
    }
}
