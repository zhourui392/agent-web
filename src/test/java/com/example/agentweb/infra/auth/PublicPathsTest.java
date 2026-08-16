package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PublicPaths} 白名单单测,两个认证 Filter 共用的真相源,零容器纯函数判断。
 *
 * @author zhourui(V33215020)
 */
public class PublicPathsTest {

    @Test
    void arbitraryFilesystemImage_requiresLogin() {
        assertFalse(PublicPaths.isPublic("/api/fs/image"));
        assertTrue(PublicPaths.isPublic("/api/share/token/image"));
    }

    @Test
    void otherFsEndpoints_stayProtected() {
        // 只精确放行 image,不放行整个 /api/fs:upload/delete/list 仍需登录
        assertFalse(PublicPaths.isPublic("/api/fs/upload"), "/api/fs/upload 不应公开");
        assertFalse(PublicPaths.isPublic("/api/fs/upload-image"), "/api/fs/upload-image 不应公开");
        assertFalse(PublicPaths.isPublic("/api/fs/delete"), "/api/fs/delete 不应公开");
        assertFalse(PublicPaths.isPublic("/api/fs/list"), "/api/fs/list 不应公开");
        assertFalse(PublicPaths.isPublic("/api/fs/download"), "/api/fs/download 不应公开");
    }

    @Test
    void shareViewerCorePaths_arePublic() {
        // /share.html 是匿名分享查看页, 必须与 /api/share/ 一样无需登录;
        // 否则单机 JAR 模式下分享链接全部 302 到登录页。
        assertTrue(PublicPaths.isPublic("/share.html"));
        assertTrue(PublicPaths.isPublic("/api/share/abc123"));
    }

    @Test
    void staticAssetDirs_arePublic() {
        // 单机 JAR 模式由后端直接服务 frontend/dist; /login.html 等公开页引用的
        // /assets/、/css/ 必须放行, 否则登录页自身资源被 302, 形成无限重定向。
        // Caddy 模式下这些请求由 file_server 拦截, 到不了后端, 白名单无影响。
        assertTrue(PublicPaths.isPublic("/assets/index-D1vUlBIU.js"));
        assertTrue(PublicPaths.isPublic("/assets/index-C7hiMiSR.css"));
        assertTrue(PublicPaths.isPublic("/css/app.css"));
    }

    @Test
    void adminStaticShell_isPublic() {
        // /admin 静态壳不含敏感数据, 公开以便展示登录/无权限状态;
        // 管理数据接口不在此列, 仍由会话 + ADMIN 角色双重校验。
        assertTrue(PublicPaths.isPublic("/admin"));
        assertTrue(PublicPaths.isPublic("/admin/"));
        assertTrue(PublicPaths.isPublic("/admin/dashboard.html"));
        assertTrue(PublicPaths.isPublic("/admin/conversations.html"));
    }

    @Test
    void adminShellPrefix_requiresSlashBoundary() {
        // /admin 仅精确匹配或带斜杠前缀, 不得宽匹配到 /admin-xxx 之类异名路径。
        assertFalse(PublicPaths.isPublic("/admin-secret"), "/admin-secret 不应被误放");
        assertFalse(PublicPaths.isPublic("/administrator.html"), "/administrator.html 不应被误放");
    }

    @Test
    void loginPage_isPublic() {
        // 登录页必须公开: 未登录请求被重定向到 /login.html, 若它自身也需登录,
        // redirect 参数会无限嵌套(Tomcat HeadersTooLargeException)。
        assertTrue(PublicPaths.isPublic("/login.html"));
    }

    @Test
    void mainAppShells_stayProtected() {
        // 主应用壳(首页/工作台)仍要求登录, 只有登录页、分享页、管理壳公开。
        assertFalse(PublicPaths.isPublic("/"), "/ 不应公开");
        assertFalse(PublicPaths.isPublic("/index.html"), "/index.html 不应公开");
        assertFalse(PublicPaths.isPublic("/workbench.html"), "/workbench.html 不应公开");
        assertFalse(PublicPaths.isPublic("/git-settings.html"), "/git-settings.html 不应公开");
    }

    @Test
    void credentialLoginEndpoints_arePublic() {
        assertTrue(PublicPaths.isPublic("/api/auth/login"));
        assertTrue(PublicPaths.isPublic("/api/auth/logout"));
        assertTrue(PublicPaths.isPublic("/api/auth/status"));
        assertFalse(PublicPaths.isPublic("/api/auth/manual-login"));
    }

    @Test
    void adminDataEndpoints_requireNormalSessionBeforeRoleCheck() {
        assertFalse(PublicPaths.isPublic("/api/metrics/overview"));
        assertFalse(PublicPaths.isPublic("/api/admin-users"));
        assertFalse(PublicPaths.isPublic("/api/admin-settings"));
    }

    @Test
    void adminPrefixes_requireTrailingSlash_notSubstringMatch() {
        // 防御回归:前缀必须带 / 边界,否则 /api/admin-control、/api/metrics-internal 之类
        // 异名接口会被误放成公开。若未来有人去掉斜杠改成宽匹配,这条断言会立刻报警。
        assertFalse(PublicPaths.isPublic("/api/admin-control"), "/api/admin-control 不应公开");
        assertFalse(PublicPaths.isPublic("/api/metrics-internal"), "/api/metrics-internal 不应公开");
        assertFalse(PublicPaths.isPublic("/api/admin-settings-debug"),
                "/api/admin-settings-debug 不应公开");
    }

    @Test
    void adminProperties_shouldNoLongerProtectRetiredWorkflowEndpoints() {
        AdminProperties properties = new AdminProperties();

        assertFalse(properties.getProtectedPrefixes().stream()
                .anyMatch(prefix -> prefix.contains("workflow")));
    }

}
