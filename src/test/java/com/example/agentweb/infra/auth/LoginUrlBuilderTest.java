package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link LoginUrlBuilder} 本地登录页地址测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
class LoginUrlBuilderTest {

    @Test
    void should_ReturnLocalLoginPage_When_ExternalPageIsNotConfigured() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat");

        // When
        String url = LoginUrlBuilder.loginPage(request, new AuthProperties());

        // Then
        assertEquals("/login.html?redirect=%2Fchat", url);
    }

    @Test
    void should_FallbackToHome_When_OriginalRequestIsApi() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/session");

        // When
        String url = LoginUrlBuilder.loginPage(request, new AuthProperties());

        // Then
        assertEquals("/login.html?redirect=%2F", url);
    }

    @Test
    void should_UseConfiguredLoginPage_When_ExternalPageIsConfigured() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat");
        AuthProperties properties = new AuthProperties();
        properties.setLoginPageUrl("https://agent.example.com/login.html");

        // When
        String url = LoginUrlBuilder.loginPage(request, properties);

        // Then
        assertEquals("https://agent.example.com/login.html?redirect=%2Fchat", url);
    }

    @Test
    void should_PreserveContextPath_When_ApplicationUsesPathPrefix() {
        // Given
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/qa/chat");
        request.setContextPath("/qa");

        // When
        String url = LoginUrlBuilder.loginPage(request, new AuthProperties());

        // Then
        assertEquals("/qa/login.html?redirect=%2Fqa%2Fchat", url);
    }
}
