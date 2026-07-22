package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.LoginAttemptLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} 本地登录 HTTP 契约测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
@WebMvcTest(AuthController.class)
@Import(GlobalExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuthProperties authProperties;

    @MockBean
    private AuthAppService authAppService;

    @MockBean
    private LoginAttemptLimiter loginAttemptLimiter;

    @BeforeEach
    void allowLoginAttempt() {
        when(loginAttemptLimiter.retryAfterSeconds(anyString(), anyString())).thenReturn(0L);
    }

    @Test
    void should_WriteHardenedSessionCookie_When_CredentialsAreValid() throws Exception {
        // Given
        ManualSession session = mock(ManualSession.class);
        when(authProperties.getCookieName()).thenReturn("local_session");
        when(authProperties.getSessionTtlSeconds()).thenReturn(604800L);
        when(session.getSessionId()).thenReturn("session-token");
        when(session.getUserId()).thenReturn("admin");
        when(session.getUserName()).thenReturn("admin");
        when(authAppService.login("admin", "secret")).thenReturn(Optional.of(session));

        // When / Then
        mvc.perform(post("/api/auth/login")
                        .secure(true)
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.10");
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("local_session", "session-token"))
                .andExpect(cookie().httpOnly("local_session", true))
                .andExpect(cookie().secure("local_session", true))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andExpect(jsonPath("$.userId").value("admin"))
                .andExpect(jsonPath("$.userName").value("admin"));
        verify(loginAttemptLimiter).recordSuccess("192.0.2.10", "admin");
    }

    @Test
    void should_WriteSecureSessionCookie_When_PublicTransportRequiresHttps() throws Exception {
        ManualSession session = mock(ManualSession.class);
        when(authProperties.getCookieName()).thenReturn("__Host-agent_session");
        when(authProperties.getSessionTtlSeconds()).thenReturn(604800L);
        when(authProperties.isCookieSecure()).thenReturn(true);
        when(session.getSessionId()).thenReturn("session-token");
        when(session.getUserId()).thenReturn("admin");
        when(session.getUserName()).thenReturn("admin");
        when(authAppService.login("admin", "secret")).thenReturn(Optional.of(session));

        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().secure("__Host-agent_session", true));
    }

    @Test
    void should_ReturnGenericUnauthorized_When_CredentialsAreInvalid() throws Exception {
        when(authAppService.login("admin", "wrong")).thenReturn(Optional.empty());

        mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.11");
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
        verify(loginAttemptLimiter).recordFailure("192.0.2.11", "admin");
    }

    @Test
    void should_Return429WithoutAuthenticating_When_LoginAttemptIsBlocked() throws Exception {
        when(loginAttemptLimiter.retryAfterSeconds("192.0.2.12", "admin")).thenReturn(73L);

        mvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.12");
                            return request;
                        })
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "73"))
                .andExpect(jsonPath("$.error").value("登录尝试过于频繁，请稍后重试"));

        verify(authAppService, never()).login("admin", "wrong");
    }

    @Test
    void should_ReturnBadRequest_When_LoginPayloadIsBlank() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void should_ReturnNotFound_When_LegacyManualLoginIsRequested() throws Exception {
        mvc.perform(post("/api/auth/manual-login")
                        .contentType("application/json")
                        .content("{\"employeeId\":\"E10001\",\"userName\":\"张三\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void should_ClearLocalSessionCookie_When_LoggingOut() throws Exception {
        // Given
        when(authProperties.getCookieName()).thenReturn("local_session");

        // When / Then
        mvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("local_session", "session-token")))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("local_session", 0))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.loginUrl").exists());
        verify(authAppService).logout("session-token");
    }

    @Test
    void should_ClearSessionCookieSecurely_When_PublicTransportRequiresHttps() throws Exception {
        when(authProperties.getCookieName()).thenReturn("__Host-agent_session");
        when(authProperties.isCookieSecure()).thenReturn(true);

        mvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-agent_session", "session-token")))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("__Host-agent_session", 0))
                .andExpect(cookie().secure("__Host-agent_session", true));
    }

    @Test
    void should_ReportManualMode_When_LocalSessionIsValid() throws Exception {
        // Given
        when(authProperties.getCookieName()).thenReturn("local_session");
        when(authAppService.resolveUser("session-token"))
                .thenReturn(Optional.of(new LoginUser("admin", "admin", null,
                        com.example.agentweb.domain.auth.UserRole.ADMIN)));

        // When / Then
        mvc.perform(get("/api/auth/status")
                        .cookie(new jakarta.servlet.http.Cookie("local_session", "session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"))
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("admin"))
                .andExpect(jsonPath("$.username").value("admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void should_ReturnLoginUrl_When_LocalSessionIsMissing() throws Exception {
        // Given
        when(authProperties.getCookieName()).thenReturn("local_session");
        when(authAppService.resolveUser(null)).thenReturn(Optional.empty());

        // When / Then
        mvc.perform(get("/api/auth/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.loginUrl").exists());
    }

    @Test
    void should_ReturnNotFound_When_RemoteLoginEndpointIsRequested() throws Exception {
        mvc.perform(get("/api/auth/sso-login-url"))
                .andExpect(status().isNotFound());
    }
}
