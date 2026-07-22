package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UserRegistrationService} 用户注册领域服务测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class UserRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-22T08:00:00Z");

    private RecordingRepository repository;
    private UserRegistrationService service;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        service = new UserRegistrationService(
                repository, new StubPasswordHasher(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void register_should_CreateAndPersistAccount_WhenUsernameIsAvailable() {
        UserAccount created = service.register(
                "  zhangsan  ", "A-secure-password!2026", UserRole.USER);

        assertEquals(created, repository.saved);
        assertEquals("zhangsan", created.getUsername());
        assertEquals("encoded:A-secure-password!2026", created.getPasswordHash());
        assertEquals(UserRole.USER, created.getRole());
        assertEquals(NOW, created.getCreatedAt());
    }

    @Test
    void register_should_RejectCaseInsensitiveDuplicateUsernameWithoutSaving() {
        repository.existing = UserAccount.restore(
                "existing-id", "ZhangSan", "encoded", UserRole.USER, true, NOW, NOW);

        UsernameAlreadyExistsException exception = assertThrows(
                UsernameAlreadyExistsException.class,
                () -> service.register(" zhangsan ", "A-secure-password!2026", UserRole.USER));

        assertEquals("用户名已存在", exception.getMessage());
        assertNull(repository.saved);
    }

    private static final class RecordingRepository implements UserAccountRepository {

        private UserAccount existing;
        private UserAccount saved;

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            if (existing != null && existing.getUsername().equalsIgnoreCase(username.trim())) {
                return Optional.of(existing);
            }
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

    private static final class StubPasswordHasher implements PasswordHasher {

        @Override
        public String encode(String rawPassword) {
            return "encoded:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String encodedPassword) {
            return ("encoded:" + rawPassword).equals(encodedPassword);
        }
    }
}
