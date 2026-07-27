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
     * <p>前端静态文件（/login.html、/share.html、/admin/*、/css/、/assets/）由 Caddy
     * file_server 直接提供，不经过后端 SessionAuthFilter，因此不在白名单内。
     * 此处只保留 API 级别的公开端点。
     */
    public static boolean isPublic(String path) {
        return "/api/auth/logout".equals(path)
                || "/api/auth/status".equals(path)
                || "/api/auth/login".equals(path)
                || path.startsWith("/api/share/");
    }

    /**
     * 是否为 API 路径（未认证时返回 401 而非 302 重定向）。
     */
    public static boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
