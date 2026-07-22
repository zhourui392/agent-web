package com.example.agentweb.domain.auth;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserAccount} 聚合根测试：账户不变量与登录语义。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class UserAccountTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");

    @Test
    void restore_should_RejectInvalidPersistentState() {
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.restore("", "admin", "hash", UserRole.ADMIN, true, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.restore("id", " ", "hash", UserRole.ADMIN, true, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.restore("id", "admin", "", UserRole.ADMIN, true, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> UserAccount.restore("id", "admin", "hash", null, true, NOW, NOW));
    }

    @Test
    void authenticate_should_AcceptMatchingPasswordForEnabledAccount() {
        UserAccount account = account(true);
        PasswordVerifier verifier = (raw, encoded) -> "correct".equals(raw) && "bcrypt-hash".equals(encoded);

        assertTrue(account.authenticate("correct", verifier));
        assertEquals("admin", account.getUsername());
        assertTrue(account.isAdmin());
    }

    @Test
    void authenticate_should_RejectWrongPasswordOrDisabledAccount() {
        PasswordVerifier verifier = (raw, encoded) -> "correct".equals(raw);

        assertFalse(account(true).authenticate("wrong", verifier));
        assertFalse(account(false).authenticate("correct", verifier));
    }

    @Test
    void changePassword_should_ReturnAccountWithNewHashAndUpdatedTimestamp() {
        UserAccount original = account(true);
        Instant changedAt = NOW.plusSeconds(60L);
        PasswordHasher hasher = new StubPasswordHasher();

        UserAccount changed = original.changePassword("A-new-password!2026", hasher, changedAt);

        assertEquals("encoded:A-new-password!2026", changed.getPasswordHash());
        assertEquals(changedAt, changed.getUpdatedAt());
        assertEquals(original.getId(), changed.getId());
        assertEquals(original.getUsername(), changed.getUsername());
        assertEquals(original.getRole(), changed.getRole());
        assertEquals(original.isEnabled(), changed.isEnabled());
        assertEquals(original.getCreatedAt(), changed.getCreatedAt());
        assertEquals("bcrypt-hash", original.getPasswordHash());
    }

    @Test
    void changePassword_should_RejectPasswordOutsideAllowedLength() {
        PasswordHasher hasher = new StubPasswordHasher();

        assertThrows(IllegalArgumentException.class,
                () -> account(true).changePassword(null, hasher, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> account(true).changePassword("short", hasher, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> account(true).changePassword("            ", hasher, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> account(true).changePassword(repeatedCharacter(257), hasher, NOW));
    }

    private UserAccount account(boolean enabled) {
        return UserAccount.restore("admin", "admin", "bcrypt-hash", UserRole.ADMIN, enabled, NOW, NOW);
    }

    private String repeatedCharacter(int length) {
        return new String(new char[length]).replace('\0', 'x');
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
