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
    void should_NormalizeDuplicateSlashes_When_GatewayForwardsDirtyUri() {
        // Given: 网关转发产生的脏 URL, 不归一会让登录页失配 PublicPaths 造成无限重定向
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "//chat");
        request.setRequestURI("//chat");

        // When
        String url = LoginUrlBuilder.loginPage(request, new AuthProperties());

        // Then
        assertEquals("/login.html?redirect=%2Fchat", url);
    }
}
