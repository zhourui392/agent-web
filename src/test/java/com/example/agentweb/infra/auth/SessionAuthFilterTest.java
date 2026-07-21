package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SessionAuthFilter} 本地会话鉴权测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
class SessionAuthFilterTest {

    private AuthAppService authAppService;
    private ThreadLocalUserContext userContext;
    private SessionAuthFilter filter;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setCookieName("local_session");
        authAppService = mock(AuthAppService.class);
        userContext = mock(ThreadLocalUserContext.class);
        filter = new SessionAuthFilter(properties, authAppService, userContext);
    }

    @Test
    void should_ContinueWithoutAuthentication_When_PathIsPublic() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login.html");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(chain).doFilter(request, response);
    }

    @Test
    void should_BindAndClearUser_When_LocalSessionIsValid() throws Exception {
        // Given
        LoginUser user = new LoginUser("E10001", "张三", null);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat");
        request.setCookies(new jakarta.servlet.http.Cookie("local_session", "session-token"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(authAppService.resolveUser("session-token")).thenReturn(Optional.of(user));

        // When
        filter.doFilter(request, response, chain);

        // Then
        verify(userContext).bind(user);
        verify(chain).doFilter(request, response);
        verify(userContext).clear();
    }

    @Test
    void should_ReturnUnauthorized_When_ApiSessionIsMissing() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/session");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authAppService.resolveUser(null)).thenReturn(Optional.empty());

        // When
        filter.doFilter(request, response, mock(FilterChain.class));

        // Then
        assertEquals(401, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
    }

    @Test
    void should_RedirectToLoginPage_When_PageSessionIsMissing() throws Exception {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(authAppService.resolveUser(null)).thenReturn(Optional.empty());

        // When
        filter.doFilter(request, response, mock(FilterChain.class));

        // Then
        assertEquals("/login.html?redirect=%2Fchat", response.getRedirectedUrl());
    }
}
