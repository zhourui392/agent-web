package com.example.agentweb.app.git;

import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GitEnvResolver} 分支单测：Mock repo + cipher，真实 {@link GitConfigPolicy}。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class GitEnvResolverTest {

    private static final Instant NOW = Instant.parse("2026-06-07T10:00:00Z");

    private UserGitConfigRepository repository;
    private CredentialCipher cipher;
    private GitEnvResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(UserGitConfigRepository.class);
        cipher = mock(CredentialCipher.class);
        resolver = new GitEnvResolver(repository, new GitConfigPolicy(), cipher);
    }

    @Test
    void null_user_should_return_empty() {
        assertTrue(resolver.resolve(null).isEmpty());
        verify(repository, never()).findByUserId(anyString());
    }

    @Test
    void unconfigured_user_should_return_empty() {
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());
        assertTrue(resolver.resolve("V33215020").isEmpty());
    }

    @Test
    void configured_user_without_credential_should_return_identity_env_only() {
        UserGitConfig cfg = UserGitConfig.create("V33215020", GitIdentity.of("周锐", "zhourui@x.com"), NOW);
        when(repository.findByUserId("V33215020")).thenReturn(Optional.of(cfg));

        GitEnvSpec spec = resolver.resolve("V33215020");

        assertEquals("周锐", spec.getIdentityEnv().get("GIT_AUTHOR_NAME"));
        assertEquals("zhourui@x.com", spec.getIdentityEnv().get("GIT_AUTHOR_EMAIL"));
        assertEquals("周锐", spec.getIdentityEnv().get("GIT_COMMITTER_NAME"));
        assertEquals("zhourui@x.com", spec.getIdentityEnv().get("GIT_COMMITTER_EMAIL"));
        assertFalse(spec.hasCredential());
        verify(cipher, never()).decrypt(anyString());
    }

    @Test
    void configured_user_with_credential_and_cipher_enabled_should_decrypt() {
        UserGitConfig cfg = UserGitConfig.create("V33215020", GitIdentity.of("周锐", "zhourui@x.com"), NOW);
        cfg.updateCredential("gituser", "v1:enc", NOW);
        when(repository.findByUserId("V33215020")).thenReturn(Optional.of(cfg));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("v1:enc")).thenReturn("secret-token");

        GitEnvSpec spec = resolver.resolve("V33215020");

        assertTrue(spec.hasCredential());
        assertEquals("gituser", spec.getCredentialUsername());
        assertEquals("secret-token", spec.getCredentialPassword());
    }

    @Test
    void credential_present_but_cipher_disabled_should_yield_identity_only() {
        UserGitConfig cfg = UserGitConfig.create("V33215020", GitIdentity.of("周锐", "zhourui@x.com"), NOW);
        cfg.updateCredential("gituser", "v1:enc", NOW);
        when(repository.findByUserId("V33215020")).thenReturn(Optional.of(cfg));
        when(cipher.isEnabled()).thenReturn(false);

        GitEnvSpec spec = resolver.resolve("V33215020");

        assertFalse(spec.hasCredential());
        assertFalse(spec.getIdentityEnv().isEmpty());
        verify(cipher, never()).decrypt(any());
    }

    @Test
    void decrypt_failure_should_degrade_to_identity_only() {
        UserGitConfig cfg = UserGitConfig.create("V33215020", GitIdentity.of("周锐", "zhourui@x.com"), NOW);
        cfg.updateCredential("gituser", "v1:enc", NOW);
        when(repository.findByUserId("V33215020")).thenReturn(Optional.of(cfg));
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.decrypt("v1:enc")).thenThrow(new IllegalStateException("boom"));

        GitEnvSpec spec = resolver.resolve("V33215020");

        assertFalse(spec.hasCredential());
        assertFalse(spec.getIdentityEnv().isEmpty());
    }
}
