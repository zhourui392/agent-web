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
    void shareViewerImage_isPublic() {
        // 分享页 share.html 渲染消息图片走 /api/fs/image,未登录访问者必须放行
        assertTrue(PublicPaths.isPublic("/api/fs/image"),
                "/api/fs/image 应在白名单,否则分享页图片被 登录会话 拦成 401");
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
    void adminLoginEndpoints_arePublic() {
        // /api/admin/** 是管理口令登录入口,登录会话 全拦时若不放行,用户连登录接口都打不开 → 死循环
        assertTrue(PublicPaths.isPublic("/api/admin/login"));
        assertTrue(PublicPaths.isPublic("/api/admin/logout"));
        assertTrue(PublicPaths.isPublic("/api/admin/status"));
    }

    @Test
    void adminGuardedDataEndpoints_arePublic() {
        // metrics/admin-user-suggestions/admin-workflows/admin-settings 由 AdminAuthFilter 用独立口令承担鉴权,
        // 登录会话 必须放行避免双重拦截 (enforce-all-hosts=true 时尤其关键)
        assertTrue(PublicPaths.isPublic("/api/metrics/overview"));
        assertTrue(PublicPaths.isPublic("/api/admin-user-suggestions"));
        assertTrue(PublicPaths.isPublic("/api/admin-user-suggestions/"));
        assertTrue(PublicPaths.isPublic("/api/admin-workflows"));
        assertTrue(PublicPaths.isPublic("/api/admin-workflows/wf-1"));
        assertTrue(PublicPaths.isPublic("/api/admin-workflow-executions"));
        assertTrue(PublicPaths.isPublic("/api/admin-workflow-executions/exec-1"));
        assertTrue(PublicPaths.isPublic("/api/admin-settings"));
        assertTrue(PublicPaths.isPublic("/api/admin-settings/agent-models"));
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

    @Test
    void scmWebhookAndExternalIntake_arePublicExactOnly() {
        // M2: GitLab 回调与外部建需求各有独立鉴权(secret / X-API-Key), 登录会话 必须放行
        assertTrue(PublicPaths.isPublic("/api/scm/webhook"), "/api/scm/webhook 必须公开");
        assertTrue(PublicPaths.isPublic("/api/requirements/external"),
                "/api/requirements/external 必须公开");
        // 精确匹配:需求线其余端点仍受 登录会话 保护
        assertFalse(PublicPaths.isPublic("/api/requirements"), "/api/requirements 不应公开");
        assertFalse(PublicPaths.isPublic("/api/requirements/R1"), "/api/requirements/{id} 不应公开");
        assertFalse(PublicPaths.isPublic("/api/scm/webhook/replay"),
                "/api/scm/webhook 子路径不应误放");
    }
}
