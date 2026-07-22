package com.example.agentweb.infra.auth;

/**
 * 公开路径白名单，{@link SessionAuthFilter} 据此放行无需登录即可访问的入口。
 *
 * @author zhourui(V33215020)
 */
public final class PublicPaths {

    private PublicPaths() {
        // util
    }

    /**
     * 是否为无需登录即可访问的路径。
     *
     * <p>放行规则按职责分组:
     * <ul>
     *   <li>分享页与静态资源：对所有访问者无条件公开</li>
     *   <li>{@code /admin} 静态壳不含敏感数据，可用于展示登录/无权限状态。</li>
     *   <li>管理数据接口不在白名单：先校验数据库会话，再校验 ADMIN 角色。</li>
     * </ul>
     */
    public static boolean isPublic(String path) {
        return "/api/auth/logout".equals(path)
                || "/api/auth/status".equals(path)
                || "/api/auth/login".equals(path)
                || "/login.html".equals(path)
                || "/share.html".equals(path)
                || "/admin".equals(path)
                || path.startsWith("/admin/")
                || path.startsWith("/api/share/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/vendor/")
                || path.startsWith("/webjars/");
    }

    /**
     * 是否为 API 路径（未认证时返回 401 而非 302 重定向）。
     */
    public static boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
