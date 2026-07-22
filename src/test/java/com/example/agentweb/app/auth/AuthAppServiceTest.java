package com.example.agentweb.app.auth;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.domain.auth.ManualSessionAuthenticator;
import com.example.agentweb.domain.auth.ManualSessionFactory;
import com.example.agentweb.domain.auth.ManualSessionRepository;
import com.example.agentweb.domain.auth.UserAccount;
import com.example.agentweb.domain.auth.UserAuthenticator;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AuthAppService} 本地会话登录编排测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class AuthAppServiceTest {

    private ManualSessionFactory sessionFactory;
    private ManualSessionAuthenticator sessionAuthenticator;
    private ManualSessionRepository sessionRepository;
    private UserAuthenticator userAuthenticator;
    private AuthAppService service;

    @BeforeEach
    public void setUp() {
        sessionFactory = new ManualSessionFactory(3600L,
                Clock.fixed(Instant.parse("2026-07-02T08:00:00Z"), ZoneOffset.UTC));
        sessionAuthenticator = org.mockito.Mockito.mock(ManualSessionAuthenticator.class);
        sessionRepository = org.mockito.Mockito.mock(ManualSessionRepository.class);
        userAuthenticator = org.mockito.Mockito.mock(UserAuthenticator.class);
        service = new AuthAppService(userAuthenticator, sessionFactory, sessionAuthenticator, sessionRepository);
    }

    @Test
    public void should_ReturnLocalUser_When_SessionTokenIsValid() {
        // Given
        when(sessionAuthenticator.authenticate("local-token"))
                .thenReturn(Optional.of(new LoginUser("V001", "本地用户", null)));

        // When
        Optional<LoginUser> user = service.resolveUser("local-token");

        // Then
        assertTrue(user.isPresent());
        assertEquals("V001", user.get().getUserId());
    }

    @Test
    public void should_ReturnEmpty_When_SessionTokenIsInvalid() {
        // Given
        when(sessionAuthenticator.authenticate("expired-token")).thenReturn(Optional.empty());

        // When
        Optional<LoginUser> user = service.resolveUser("expired-token");

        // Then
        assertFalse(user.isPresent());
    }

    @Test
    public void should_PersistSession_When_CredentialsAreValid() {
        // Given
        UserAccount account = UserAccount.restore(
                "admin", "admin", "encoded", UserRole.ADMIN, true,
                Instant.parse("2026-07-22T00:00:00Z"), Instant.parse("2026-07-22T00:00:00Z"));
        when(userAuthenticator.authenticate("admin", "secret")).thenReturn(Optional.of(account));

        // When
        ManualSession session = service.login("admin", "secret").orElseThrow(AssertionError::new);

        // Then
        ArgumentCaptor<ManualSession> captor = ArgumentCaptor.forClass(ManualSession.class);
        verify(sessionRepository).save(captor.capture());
        assertEquals(session.getSessionId(), captor.getValue().getSessionId());
        assertEquals("admin", session.getUserId());
        assertEquals("admin", session.getUserName());
    }

    @Test
    public void should_NotPersistSession_When_CredentialsAreInvalid() {
        // Given
        when(userAuthenticator.authenticate("admin", "wrong")).thenReturn(Optional.empty());

        // When
        Optional<ManualSession> result = service.login("admin", "wrong");

        // Then
        assertFalse(result.isPresent());
        verify(sessionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void should_DeleteLocalSession_When_LoggingOutWithToken() {
        // Given
        String sessionToken = "local-token";

        // When
        service.logout(sessionToken);

        // Then
        verify(sessionRepository).deleteById(sessionToken);
    }

    @Test
    public void should_NotDeleteSession_When_LoggingOutWithoutToken() {
        // When
        service.logout(null);

        // Then
        verify(sessionRepository, never()).deleteById(null);
    }
}
