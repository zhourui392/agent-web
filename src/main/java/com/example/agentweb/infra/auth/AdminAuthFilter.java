package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 管理后台数据接口的 ADMIN 角色鉴权，运行在 {@link SessionAuthFilter} 之后。
 *
 * <p>会话缺失时 fail-closed 返回 401，已登录但不是 ADMIN 时返回 403。</p>
 *
 * <p>经 {@link AdminSecurityConfig} 的 FilterRegistrationBean 注册(非 @Component),
 * 以免被 {@code @WebMvcTest} 切片自动纳入、逼每个控制器切片补构造依赖 mock。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAuthFilter implements Filter {

    private static final String REQUIRED_ADMIN_WORKBENCH_PREFIX =
            "/api/admin/workbenches";

    private final UserContext userContext;
    private final AdminProperties properties;

    public AdminAuthFilter(UserContext userContext, AdminProperties properties) {
        this.userContext = userContext;
        this.properties = properties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        // 归一斜杠再匹配受保护前缀,脏 URL(//admin/...)不得绕过
        if (!matchesProtected(RequestPath.normalized(req))) {
            chain.doFilter(request, response);
            return;
        }

        LoginUser user = userContext.currentUser().orElse(null);
        if (user == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"未登录\"}");
            return;
        }
        if (!user.isAdmin()) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("application/json;charset=UTF-8");
            resp.getWriter().write("{\"error\":\"无管理员权限\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** URI 命中任一受保护前缀即需口令把关;前缀集合来自 {@link AdminProperties}。 */
    private boolean matchesProtected(String uri) {
        if (matchesPrefix(uri, REQUIRED_ADMIN_WORKBENCH_PREFIX)) {
            return true;
        }
        for (String prefix : properties.getProtectedPrefixes()) {
            if (matchesPrefix(uri, prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesPrefix(String uri, String prefix) {
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }
}
