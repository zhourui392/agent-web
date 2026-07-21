package com.example.agentweb.domain.git;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UserGitConfig} 聚合不变量与状态迁移单测（零 Mock）。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class UserGitConfigTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    @Test
    void create_should_require_identity_and_userId() {
        assertThrows(IllegalArgumentException.class,
                () -> UserGitConfig.create("u1", null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> UserGitConfig.create("  ", GitIdentity.of("n", "a@b.com"), NOW));
    }

    @Test
    void create_should_start_without_credential() {
        UserGitConfig cfg = UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW);
        assertFalse(cfg.hasCredential());
        assertEquals("u1", cfg.getUserId());
        assertEquals("n", cfg.getIdentity().getName());
    }

    @Test
    void updateCredential_should_validate_and_set() {
        UserGitConfig cfg = UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW);

        assertThrows(IllegalArgumentException.class,
                () -> cfg.updateCredential(" ", "cipher", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> cfg.updateCredential("user", " ", NOW));

        cfg.updateCredential("gituser", "v1:abc", NOW);
        assertTrue(cfg.hasCredential());
        assertEquals("gituser", cfg.getCredentialUsername());
        assertEquals("v1:abc", cfg.getCredentialPasswordCipher());
    }

    @Test
    void clearCredential_should_drop_credential_but_keep_identity() {
        UserGitConfig cfg = UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW);
        cfg.updateCredential("gituser", "v1:abc", NOW);

        cfg.clearCredential(NOW);

        assertFalse(cfg.hasCredential());
        assertEquals("n", cfg.getIdentity().getName());
    }

    @Test
    void updateIdentity_should_replace_identity() {
        UserGitConfig cfg = UserGitConfig.create("u1", GitIdentity.of("n", "a@b.com"), NOW);
        cfg.updateIdentity(GitIdentity.of("新名", "new@example.com"), NOW);
        assertEquals("新名", cfg.getIdentity().getName());
        assertEquals("new@example.com", cfg.getIdentity().getEmail());
    }
}
