package com.example.agentweb.app.git;

import com.example.agentweb.domain.auth.CurrentUserProvider;
import com.example.agentweb.domain.git.GitConfigPolicy;
import com.example.agentweb.domain.git.GitIdentity;
import com.example.agentweb.domain.git.UserGitConfig;
import com.example.agentweb.domain.git.UserGitConfigRepository;
import com.example.agentweb.infra.git.GitCredentialCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GitConfigAppService} 编排 + 授权闸单测：Mock repo + cipher + currentUser，
 * 真实 {@link GitConfigPolicy} 与真实 Domain 对象。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
class GitConfigAppServiceTest {

    private UserGitConfigRepository repository;
    private CurrentUserProvider currentUserProvider;
    private GitCredentialCipher cipher;
    private GitConfigAppService service;

    @BeforeEach
    void setUp() {
        repository = mock(UserGitConfigRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        cipher = mock(GitCredentialCipher.class);
        service = new GitConfigAppService(repository, new GitConfigPolicy(), currentUserProvider, cipher);
    }

    @Test
    void save_by_null_user_should_throw() {
        when(currentUserProvider.currentUserId()).thenReturn(null);
        assertThrows(IllegalStateException.class,
                () -> service.save("n", "a@b.com", null, null));
    }

    @Test
    void save_invalid_email_should_throw_illegal_argument() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.save("n", "bad-email", null, null));
    }

    @Test
    void save_identity_only_should_persist_identity_without_credential() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());

        service.save("周锐", "zhourui@x.com", null, null);

        ArgumentCaptor<UserGitConfig> captor = ArgumentCaptor.forClass(UserGitConfig.class);
        verify(repository).save(captor.capture());
        UserGitConfig saved = captor.getValue();
        assertEquals("周锐", saved.getIdentity().getName());
        assertEquals("zhourui@x.com", saved.getIdentity().getEmail());
        assertFalse(saved.hasCredential());
        verify(cipher, never()).encrypt(anyString());
    }

    @Test
    void save_with_credential_and_cipher_enabled_should_encrypt_and_persist() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());
        when(cipher.isEnabled()).thenReturn(true);
        when(cipher.encrypt("tok")).thenReturn("v1:cipher");

        service.save("周锐", "zhourui@x.com", "gituser", "tok");

        ArgumentCaptor<UserGitConfig> captor = ArgumentCaptor.forClass(UserGitConfig.class);
        verify(repository).save(captor.capture());
        UserGitConfig saved = captor.getValue();
        assertTrue(saved.hasCredential());
        assertEquals("gituser", saved.getCredentialUsername());
        assertEquals("v1:cipher", saved.getCredentialPasswordCipher());
    }

    @Test
    void save_with_credential_but_cipher_disabled_should_throw() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());
        when(cipher.isEnabled()).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> service.save("周锐", "zhourui@x.com", "gituser", "tok"));
        verify(repository, never()).save(any());
    }

    @Test
    void save_password_without_username_should_throw() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());
        when(cipher.isEnabled()).thenReturn(true);

        assertThrows(IllegalArgumentException.class,
                () -> service.save("周锐", "zhourui@x.com", "  ", "tok"));
    }

    @Test
    void getForCurrentUser_without_user_context_should_be_read_only() {
        when(currentUserProvider.currentUserId()).thenReturn(null);
        GitConfigView view = service.getForCurrentUser();
        assertTrue(view.isReadOnly());
        verify(repository, never()).findByUserId(anyString());
    }

    @Test
    void getForCurrentUser_configured_user_should_return_identity_and_credential_flag() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        UserGitConfig cfg = UserGitConfig.create("V33215020", GitIdentity.of("周锐", "zhourui@x.com"),
                Instant.now());
        cfg.updateCredential("gituser", "v1:enc", Instant.now());
        when(repository.findByUserId("V33215020")).thenReturn(Optional.of(cfg));

        GitConfigView view = service.getForCurrentUser();

        assertFalse(view.isReadOnly());
        assertEquals("周锐", view.getName());
        assertEquals("zhourui@x.com", view.getEmail());
        assertTrue(view.isCredentialConfigured());
    }

    @Test
    void getForCurrentUser_user_without_config_should_be_editable_empty() {
        when(currentUserProvider.currentUserId()).thenReturn("V33215020");
        when(repository.findByUserId("V33215020")).thenReturn(Optional.empty());

        GitConfigView view = service.getForCurrentUser();

        assertFalse(view.isReadOnly());
        assertEquals("", view.getName());
        assertFalse(view.isCredentialConfigured());
    }
}
