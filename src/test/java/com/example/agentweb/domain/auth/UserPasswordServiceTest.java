package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link UserPasswordService} 改密与会话失效领域编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class UserPasswordServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T02:00:00Z");

    @Test
    void changePassword_should_SaveNewHashAndRevokeExistingSessions() {
        RecordingUserRepository userRepository = new RecordingUserRepository();
        RecordingSessionRepository sessionRepository = new RecordingSessionRepository();
        PasswordHasher passwordHasher = new StubPasswordHasher();
        UserPasswordService service = new UserPasswordService(
                userRepository, sessionRepository, passwordHasher, Clock.fixed(NOW, ZoneOffset.UTC));
        UserAccount account = UserAccount.restore("admin", "admin", "old-hash", UserRole.ADMIN,
                true, NOW.minusSeconds(3600L), NOW.minusSeconds(3600L));

        service.changePassword(account, "A-new-password!2026");

        assertEquals("encoded:A-new-password!2026", userRepository.saved.getPasswordHash());
        assertEquals(NOW, userRepository.saved.getUpdatedAt());
        assertEquals("admin", sessionRepository.revokedUserId);
    }

    private static final class RecordingUserRepository implements UserAccountRepository {
        private UserAccount saved;

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return Optional.empty();
        }

        @Override
        public Optional<UserAccount> findById(String id) {
            return Optional.empty();
        }

        @Override
        public void save(UserAccount account) {
            saved = account;
        }
    }

    private static final class RecordingSessionRepository implements ManualSessionRepository {
        private String revokedUserId;

        @Override
        public void save(ManualSession session) {
        }

        @Override
        public Optional<ManualSession> findById(String sessionId) {
            return Optional.empty();
        }

        @Override
        public void deleteById(String sessionId) {
        }

        @Override
        public int deleteByUserId(String userId) {
            revokedUserId = userId;
            return 1;
        }

        @Override
        public int deleteExpiredBefore(Instant threshold) {
            return 0;
        }
    }

    private static final class StubPasswordHasher implements PasswordHasher {

        @Override
        public String encode(String rawPassword) {
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String encodedPassword) {
            return false;
        }
    }
}
