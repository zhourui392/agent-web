package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserAuthenticator} 领域服务测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class UserAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void authenticate_should_ReturnAccountOnlyForValidCredentials() {
        UserAccount account = UserAccount.restore(
                "admin", "admin", "encoded", UserRole.ADMIN, true, NOW, NOW);
        UserAccountRepository repository = new StubRepository(account);
        PasswordVerifier verifier = (raw, encoded) -> "secret".equals(raw) && "encoded".equals(encoded);
        UserAuthenticator authenticator = new UserAuthenticator(repository, verifier, "dummy");

        assertTrue(authenticator.authenticate("admin", "secret").isPresent());
        assertFalse(authenticator.authenticate("admin", "wrong").isPresent());
        assertFalse(authenticator.authenticate("missing", "secret").isPresent());
    }

    @Test
    void authenticate_should_RejectDisabledAccount() {
        UserAccount account = UserAccount.restore(
                "admin", "admin", "encoded", UserRole.ADMIN, false, NOW, NOW);
        UserAuthenticator authenticator = new UserAuthenticator(
                new StubRepository(account), (raw, encoded) -> true, "dummy");

        assertFalse(authenticator.authenticate("admin", "secret").isPresent());
    }

    private static final class StubRepository implements UserAccountRepository {
        private final UserAccount account;

        private StubRepository(UserAccount account) {
            this.account = account;
        }

        @Override
        public Optional<UserAccount> findByUsername(String username) {
            return account.getUsername().equals(username) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public Optional<UserAccount> findById(String id) {
            return account.getId().equals(id) ? Optional.of(account) : Optional.empty();
        }

        @Override
        public void save(UserAccount userAccount) {
            throw new UnsupportedOperationException("test stub is read-only");
        }
    }
}
