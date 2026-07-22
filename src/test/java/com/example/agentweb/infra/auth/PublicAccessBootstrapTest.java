package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.ManualSessionRepository;
import com.example.agentweb.domain.auth.PasswordHasher;
import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserAccountRepository;
import com.example.agentweb.domain.auth.UserPasswordService;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PublicAccessBootstrap} 公网启动密码门禁测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class PublicAccessBootstrapTest {

    private static final String INITIAL_PASSWORD_HASH =
            "$2b$12$DKOR1h0GGLppD.lpcl94N.TqktMUO3Bmh19O.moh9qhPzY/..ZdR.";
    private static final Instant NOW = Instant.parse("2026-07-22T01:00:00Z");

    private final PublicAccessProperties properties = new PublicAccessProperties();
    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final ManualSessionRepository sessionRepository = mock(ManualSessionRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @BeforeEach
    void enablePublicAccess() {
        properties.setEnabled(true);
    }

    @Test
    void startup_should_DoNothing_When_PublicAccessIsDisabled() {
        properties.setEnabled(false);

        bootstrap().afterSingletonsInstantiated();

        verifyNoInteractions(repository, sessionRepository, passwordHasher);
    }

    @Test
    void startup_should_NotResetPassword_When_AdminAlreadyChangedIt() {
        when(repository.findByUsername("admin")).thenReturn(Optional.of(account("changed-hash")));

        bootstrap().afterSingletonsInstantiated();

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(UserAccount.class));
        verifyNoInteractions(sessionRepository, passwordHasher);
    }

    @Test
    void startup_should_RejectPublicStartup_When_InitialPasswordHasNoReplacement() {
        when(repository.findByUsername("admin")).thenReturn(Optional.of(account(INITIAL_PASSWORD_HASH)));

        assertThrows(IllegalStateException.class, () -> bootstrap().afterSingletonsInstantiated());

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(UserAccount.class));
    }

    @Test
    void startup_should_RejectPublicStartup_When_ReplacementEqualsKnownInitialPassword() {
        properties.setBootstrapAdminPassword("Aa135246");
        when(repository.findByUsername("admin")).thenReturn(Optional.of(account(INITIAL_PASSWORD_HASH)));
        when(passwordHasher.matches("Aa135246", INITIAL_PASSWORD_HASH)).thenReturn(true);

        assertThrows(IllegalStateException.class, () -> bootstrap().afterSingletonsInstantiated());

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any(UserAccount.class));
    }

    @Test
    void startup_should_HashAndPersistReplacement_When_InitialPasswordIsStillStored() {
        properties.setBootstrapAdminPassword("A-brand-new-password!2026");
        when(repository.findByUsername("admin")).thenReturn(Optional.of(account(INITIAL_PASSWORD_HASH)));
        when(passwordHasher.matches("A-brand-new-password!2026", INITIAL_PASSWORD_HASH)).thenReturn(false);
        when(passwordHasher.encode("A-brand-new-password!2026")).thenReturn("new-bcrypt-hash");

        bootstrap().afterSingletonsInstantiated();

        ArgumentCaptor<UserAccount> accountCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(repository).save(accountCaptor.capture());
        verify(sessionRepository).deleteByUserId("admin");
        assertEquals("new-bcrypt-hash", accountCaptor.getValue().getPasswordHash());
        assertEquals(NOW, accountCaptor.getValue().getUpdatedAt());
    }

    @Test
    void startup_should_RejectPublicStartup_When_AdminAccountIsMissing() {
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> bootstrap().afterSingletonsInstantiated());
    }

    private PublicAccessBootstrap bootstrap() {
        UserPasswordService passwordService = new UserPasswordService(
                repository, sessionRepository, passwordHasher, clock);
        return new PublicAccessBootstrap(properties, repository, passwordHasher, passwordService);
    }

    private UserAccount account(String passwordHash) {
        return UserAccount.restore("admin", "admin", passwordHash, UserRole.ADMIN, true,
                NOW.minusSeconds(3600L), NOW.minusSeconds(3600L));
    }
}
