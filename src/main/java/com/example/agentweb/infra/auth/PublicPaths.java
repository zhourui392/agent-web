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
     *   <li>{@code /api/scm/webhook}:GitLab 回调无用户会话，secret 常量时间比对在 Controller 守门(M2)</li>
     *   <li>{@code /api/requirements/external}:外部系统建需求,走 {@code X-API-Key} 由 {@code ApiKeyAuthFilter} 守门(M2)</li>
     *   <li>{@code /admin}(入口重定向)与 {@code /admin/} 管理页静态壳:页面壳本身不含敏感数据,
     *       登录卡由壳内 JS 按 {@code /api/admin/status} 渲染；会话过滤器必须放行，
     *       否则管理口令登录框无法显示。真正的闸门是数据接口的管理口令。</li>
     *   <li>{@code /api/admin/}、{@code /api/metrics/}、{@code /api/admin-user-suggestions/}、
     *       {@code /api/admin-workflows/}、{@code /api/admin-workflow-executions/}:
     *       鉴权交给 {@code AdminAuthFilter} 的独立管理口令承担,
     *       会话过滤器必须放行以避免双重拦截；尤其 {@code /api/admin/} 是管理口令登录入口。
     *       前缀必须带斜杠收尾,防 {@code /api/admin-control} 之类被误放(见对应测试)。</li>
     * </ul>
     */
    public static boolean isPublic(String path) {
        return "/api/auth/logout".equals(path)
                || "/api/auth/status".equals(path)
                || "/api/auth/manual-login".equals(path)
                || "/login.html".equals(path)
                || "/share.html".equals(path)
                || "/admin".equals(path)
                || path.startsWith("/admin/")
                || path.startsWith("/api/share/")
                || "/api/fs/image".equals(path)
                || "/api/scm/webhook".equals(path)
                || "/api/requirements/external".equals(path)
                || path.startsWith("/api/admin/")
                || path.startsWith("/api/metrics/")
                || "/api/admin-user-suggestions".equals(path)
                || path.startsWith("/api/admin-user-suggestions/")
                || "/api/admin-workflows".equals(path)
                || path.startsWith("/api/admin-workflows/")
                || "/api/admin-workflow-executions".equals(path)
                || path.startsWith("/api/admin-workflow-executions/")
                || "/api/admin-settings".equals(path)
                || path.startsWith("/api/admin-settings/")
                || "/api/admin-requirement-events".equals(path)
                || path.startsWith("/api/admin-requirement-events/")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/vendor/");
    }

    /**
     * 是否为 API 路径（未认证时返回 401 而非 302 重定向）。
     */
    public static boolean isApiPath(String path) {
        return path.startsWith("/api/");
    }
}
