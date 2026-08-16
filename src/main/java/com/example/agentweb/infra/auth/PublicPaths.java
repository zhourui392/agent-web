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
     * <p>放行规则按职责分组：
     * <ul>
     *   <li>认证 API：登录/登出/状态查询，匿名会话的入口。</li>
     *   <li>静态壳与资源：{@code /login.html}、{@code /share.html}、{@code /admin} 系列、
     *   {@code /assets/}、{@code /css/}。单机 JAR 模式由后端直接服务 frontend/dist，
     *   登录页若不在白名单会把自身套进 redirect 参数形成无限重定向；静态壳不含敏感数据，
     *   数据一律走受保护的 API。公网 Caddy 模式下这些请求由 file_server 拦截，
     *   根本到不了后端，白名单不改变公网暴露面。</li>
     *   <li>分享 API：{@code /api/share/} 匿名查看。</li>
     * </ul>
     * 主应用壳（首页、/workbench.html 等）不在白名单，仍需登录。
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
                || path.startsWith("/assets/")
                || path.startsWith("/css/");
    }

    /**
     * 是否为 API 路径（未认证时返回 401 而非 302 重定向）。
     */
    public static boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
