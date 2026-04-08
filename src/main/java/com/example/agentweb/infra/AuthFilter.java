package com.example.agentweb.infra;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

/**
 * Servlet filter that guards all pages/APIs behind session-based login
 * when {@code agent.auth.enabled=true}.
 */
@Component
@Order(1)
public class AuthFilter implements Filter {

    public static final String SESSION_ATTR_USER = "authenticated_user";

    private final AuthProperties authProperties;

    public AuthFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (!authProperties.isEnabled()) {
            chain.doFilter(req, res);
            return;
        }

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = request.getRequestURI();

        // Allow login page and login API without authentication
        if (isPublicPath(path)) {
            chain.doFilter(req, res);
            return;
        }

        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_ATTR_USER) != null) {
            chain.doFilter(req, res);
            return;
        }

        // Not authenticated
        if (isApiPath(path)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"未登录\"}");
        } else {
            response.sendRedirect("/login.html");
        }
    }

    private boolean isPublicPath(String path) {
        return "/login.html".equals(path)
                || "/api/auth/login".equals(path)
                || path.startsWith("/css/login")
                || path.startsWith("/js/login")
                || "/share.html".equals(path)
                || path.startsWith("/api/share/")
                || path.startsWith("/css/")
                || path.startsWith("/js/");
    }

    private boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
