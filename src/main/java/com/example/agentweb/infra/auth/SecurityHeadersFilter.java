package com.example.agentweb.infra.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 统一浏览器安全响应头。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class SecurityHeadersFilter implements Filter {

    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; "
                    + "form-action 'self'; img-src 'self' data: blob:; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; "
                    + "connect-src 'self'; font-src 'self' data:";

    private final boolean secureTransportRequired;

    public SecurityHeadersFilter() {
        this(false);
    }

    public SecurityHeadersFilter(boolean secureTransportRequired) {
        this.secureTransportRequired = secureTransportRequired;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        httpResponse.setHeader("X-Content-Type-Options", "nosniff");
        httpResponse.setHeader("X-Frame-Options", "DENY");
        httpResponse.setHeader("Referrer-Policy", "no-referrer");
        httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        httpResponse.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        httpResponse.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        if (secureTransportRequired || httpRequest.isSecure()) {
            httpResponse.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
