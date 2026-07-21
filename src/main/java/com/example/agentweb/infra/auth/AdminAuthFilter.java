package com.example.agentweb.infra.auth;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 管理后台数据接口({@code /api/metrics/**})的独立口令鉴权，运行在 {@link SessionAuthFilter} 之后。
 *
 * <p>普通用户会话与管理后台口令相互独立，管理台数据的真正闸门在此：
 * 校验 {@link AdminProperties#getCookieName()} Cookie 携带的会话令牌，未通过返回 401。
 * 仅拦数据接口,登录端点({@code /api/admin/**})与管理页静态壳不在此过滤,避免登录前无法进入。</p>
 *
 * <p>经 {@link AdminSecurityConfig} 的 FilterRegistrationBean 注册(非 @Component),
 * 以免被 {@code @WebMvcTest} 切片自动纳入、逼每个控制器切片补构造依赖 mock。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAuthFilter implements Filter {

    private final AdminAccessService accessService;
    private final AdminProperties properties;

    public AdminAuthFilter(AdminAccessService accessService, AdminProperties properties) {
        this.accessService = accessService;
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (!properties.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        // context-path 挂载部署(/qa)下 requestURI 含挂载前缀,剥成逻辑路径再匹配受保护前缀
        if (!matchesProtected(ContextPrefix.strip(req))) {
            chain.doFilter(request, response);
            return;
        }

        String token = readCookie(req, properties.getCookieName());
        if (!accessService.isAuthenticated(token)) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"管理口令未通过\",\"authenticated\":false}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** URI 命中任一受保护前缀即需口令把关;前缀集合来自 {@link AdminProperties}。 */
    private boolean matchesProtected(String uri) {
        for (String prefix : properties.getProtectedPrefixes()) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
