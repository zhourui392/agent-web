package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SecurityHeadersFilter} 响应安全头测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class SecurityHeadersFilterTest {

    @Test
    void should_addBrowserHardeningHeaders_andHstsForHttps() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.setSecure(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(request, response, new MockFilterChain());

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("same-origin", response.getHeader("Cross-Origin-Opener-Policy"));
        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getHeader("Content-Security-Policy").contains("object-src 'none'"));
        org.junit.jupiter.api.Assertions.assertTrue(
                response.getHeader("Content-Security-Policy")
                        .contains("script-src 'self' 'unsafe-inline' 'unsafe-eval'"));
    }

    @Test
    void should_not_addHstsForPlainHttp() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter().doFilter(
                new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());

        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void should_addHstsForProxyTerminatedHttps_When_SecureTransportIsRequired() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SecurityHeadersFilter(true).doFilter(
                new MockHttpServletRequest("GET", "/"), response, new MockFilterChain());

        assertEquals("max-age=31536000; includeSubDomains",
                response.getHeader("Strict-Transport-Security"));
    }
}
