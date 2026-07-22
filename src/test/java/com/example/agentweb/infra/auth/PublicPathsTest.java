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
        assertTrue(PublicPaths.isPublic("/share.html"));
        assertTrue(PublicPaths.isPublic("/api/share/abc123"));
    }

    @Test
    void staticAssetDirs_arePublic() {
        // login.html 是未登录入口页, 它引的第三方库都在 /vendor/ (vue/element-plus/marked),
        // 连同 /css/ /js/ 必须放行; 否则未登录浏览器请求这些资源被 302 到 login.html(HTML),
        // 浏览器当 JS 解析报 "Unexpected token '<'" → Vue is not defined → 登录页白屏。
        assertTrue(PublicPaths.isPublic("/vendor/vue.global.js"), "/vendor/ 必须公开, 否则登录页加载不了 Vue");
        assertTrue(PublicPaths.isPublic("/vendor/element-plus.full.min.js"));
        assertTrue(PublicPaths.isPublic("/css/app.css"));
        assertTrue(PublicPaths.isPublic("/js/app.js"));
    }

    @Test
    void adminStaticShell_isPublic() {
        // 管理页是 MPA 静态壳(/admin 入口重定向 + /admin/<page>.html), 登录卡由壳内 JS 渲染。
        // 登录会话 不放行的话, 未登录访问者被 302 到 登录会话 登录页, 壳加载不出来 → 管理口令登录框永不显示。
        assertTrue(PublicPaths.isPublic("/admin"), "/admin 入口重定向必须公开");
        assertTrue(PublicPaths.isPublic("/admin/"), "/admin/ 必须公开");
        assertTrue(PublicPaths.isPublic("/admin/dashboard.html"), "/admin/dashboard.html 必须公开");
        assertTrue(PublicPaths.isPublic("/admin/conversations.html"));
    }

    @Test
    void adminShellPrefix_requiresSlashBoundary() {
        // /admin 仅精确匹配或带斜杠前缀, 不得宽匹配到 /admin-xxx 之类异名路径。
        assertFalse(PublicPaths.isPublic("/admin-secret"), "/admin-secret 不应被误放");
        assertFalse(PublicPaths.isPublic("/administrator.html"), "/administrator.html 不应被误放");
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
        assertFalse(PublicPaths.isPublic("/api/admin-user-suggestions"));
        assertFalse(PublicPaths.isPublic("/api/admin-workflows"));
        assertFalse(PublicPaths.isPublic("/api/admin-workflow-executions"));
        assertFalse(PublicPaths.isPublic("/api/admin-settings"));
    }

    @Test
    void adminPrefixes_requireTrailingSlash_notSubstringMatch() {
        // 防御回归:前缀必须带 / 边界,否则 /api/admin-control、/api/metrics-internal 之类
        // 异名接口会被误放成公开。若未来有人去掉斜杠改成宽匹配,这条断言会立刻报警。
        assertFalse(PublicPaths.isPublic("/api/admin-control"), "/api/admin-control 不应公开");
        assertFalse(PublicPaths.isPublic("/api/metrics-internal"), "/api/metrics-internal 不应公开");
        assertFalse(PublicPaths.isPublic("/api/admin-user-suggestions-debug"),
                "/api/admin-user-suggestions-debug 不应公开");
        assertFalse(PublicPaths.isPublic("/api/admin-workflows-debug"),
                "/api/admin-workflows-debug 不应公开");
        assertFalse(PublicPaths.isPublic("/api/admin-workflow-executions-debug"),
                "/api/admin-workflow-executions-debug 不应公开");
        assertFalse(PublicPaths.isPublic("/api/admin-settings-debug"),
                "/api/admin-settings-debug 不应公开");
    }

}
