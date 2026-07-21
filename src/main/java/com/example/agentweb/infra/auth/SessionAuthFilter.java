package com.example.agentweb.infra.auth;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.infra.log.MdcFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

/**
 * 本地会话鉴权过滤器，为非公开页面和 API 绑定当前登录用户。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public class SessionAuthFilter implements Filter {

    private final AuthProperties authProperties;
    private final AuthAppService authAppService;
    private final ThreadLocalUserContext userContext;

    public SessionAuthFilter(AuthProperties authProperties,
                             AuthAppService authAppService,
                             ThreadLocalUserContext userContext) {
        this.authProperties = authProperties;
        this.authAppService = authAppService;
        this.userContext = userContext;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        String path = ContextPrefix.strip(httpRequest);

        if (PublicPaths.isPublic(path)) {
            chain.doFilter(request, response);
            return;
        }
        String sessionToken = readCookie(httpRequest, authProperties.getCookieName());
        LoginUser user = authAppService.resolveUser(sessionToken).orElse(null);
        if (user != null) {
            bindAndContinue(user, request, response, chain);
            return;
        }
        rejectUnauthenticated(httpRequest, httpResponse, path);
    }

    private void bindAndContinue(LoginUser user, ServletRequest request, ServletResponse response,
                                 FilterChain chain) throws IOException, ServletException {
        try {
            userContext.bind(user);
            MDC.put(MdcFilter.MDC_USER_ID, user.getUserId());
            chain.doFilter(request, response);
        } finally {
            userContext.clear();
            MDC.remove(MdcFilter.MDC_USER_ID);
        }
    }

    private void rejectUnauthenticated(HttpServletRequest request, HttpServletResponse response, String path)
            throws IOException {
        String loginUrl = LoginUrlBuilder.loginPage(request, authProperties);
        if (PublicPaths.isApiPath(path)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录\",\"loginUrl\":\""
                    + jsonEscape(loginUrl) + "\"}");
            return;
        }
        response.sendRedirect(loginUrl);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
