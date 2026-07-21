package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.ApiKeyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
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
    private ApiKeyProperties apiKeyProperties;

    @MockBean
    private AuthProperties authProperties;

    @MockBean
    private AuthAppService authAppService;

    @Test
    void should_WriteLocalSessionCookie_When_EmployeeLogsIn() throws Exception {
        // Given
        ManualSession session = mock(ManualSession.class);
        when(authProperties.getCookieName()).thenReturn("local_session");
        when(authProperties.getSessionTtlSeconds()).thenReturn(604800L);
        when(session.getSessionId()).thenReturn("session-token");
        when(session.getUserId()).thenReturn("E10001");
        when(session.getUserName()).thenReturn("张三");
        when(authAppService.manualLogin("E10001", "张三")).thenReturn(session);

        // When / Then
        mvc.perform(post("/api/auth/manual-login")
                        .contentType("application/json")
                        .content("{\"employeeId\":\"E10001\",\"userName\":\"张三\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().value("local_session", "session-token"))
                .andExpect(cookie().httpOnly("local_session", true))
                .andExpect(jsonPath("$.userId").value("E10001"))
                .andExpect(jsonPath("$.userName").value("张三"));
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
    void should_ReportManualMode_When_LocalSessionIsValid() throws Exception {
        // Given
        when(authProperties.getCookieName()).thenReturn("local_session");
        when(authAppService.resolveUser("session-token"))
                .thenReturn(Optional.of(new LoginUser("E10001", "张三", null)));

        // When / Then
        mvc.perform(get("/api/auth/status")
                        .cookie(new jakarta.servlet.http.Cookie("local_session", "session-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("manual"))
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.authEnabled").value(true))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.userId").value("E10001"))
                .andExpect(jsonPath("$.username").value("张三"));
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
