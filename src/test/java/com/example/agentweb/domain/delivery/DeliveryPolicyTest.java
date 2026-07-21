package com.example.agentweb.domain.delivery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 交付策略：push ref 白名单（只许 req/* 命名空间）、commit trailer 组装、MR 强制草稿。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class DeliveryPolicyTest {

    private final DeliveryPolicy policy = new DeliveryPolicy();

    // ---- assertPushRefAllowed：req/* 白名单 ----

    @Test
    public void pushRef_should_allow_req_namespace() {
        assertDoesNotThrow(() -> policy.assertPushRefAllowed("req/R2607040001"));
    }

    @Test
    public void pushRef_should_reject_null_and_blank() {
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed(null));
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("  "));
    }

    @Test
    public void pushRef_should_reject_outside_namespace() {
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("main"));
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("master"));
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("feature/x"));
        // 前缀相似但不是 req/ 命名空间
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("request/x"));
    }

    @Test
    public void pushRef_should_reject_empty_remainder() {
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/"));
    }

    @Test
    public void pushRef_should_reject_refspec_injection() {
        // 冒号 = refspec 分隔符,推到别的远端分支
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/a:main"));
        // 通配符 = 批量推
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/*"));
        // 空白 = 命令拼接面
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/a b"));
        // 路径回溯
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/../main"));
        // 以 - 开头的段可被误读为 git 选项
        assertThrows(PushRefForbiddenException.class, () -> policy.assertPushRefAllowed("req/-f"));
    }

    // ---- buildCommitTrailers ----

    @Test
    public void trailers_personal_credential_should_only_carry_session_link() {
        List<String> trailers = policy.buildCommitTrailers(
                "http://host/session/abc", "V33215020", false);

        assertEquals(List.of("Agent-Web-Session: http://host/session/abc"), trailers);
    }

    @Test
    public void trailers_default_account_should_append_operated_by() {
        List<String> trailers = policy.buildCommitTrailers(
                "http://host/session/abc", "V33215020", true);

        assertEquals(List.of(
                "Agent-Web-Session: http://host/session/abc",
                "Operated-By: V33215020"), trailers);
    }

    @Test
    public void trailers_should_require_session_url() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.buildCommitTrailers(" ", "V33215020", false));
        assertThrows(IllegalArgumentException.class,
                () -> policy.buildCommitTrailers(null, "V33215020", false));
    }

    @Test
    public void trailers_default_account_should_require_actor() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.buildCommitTrailers("http://host/s/1", " ", true));
        // 个人凭证时 actor 可空(身份即凭证本人)
        assertDoesNotThrow(() -> policy.buildCommitTrailers("http://host/s/1", null, false));
    }

    // ---- draftTitle：MR 一律草稿创建 ----

    @Test
    public void draftTitle_should_prefix_when_missing() {
        assertEquals("Draft: feat: xxx", policy.draftTitle("feat: xxx"));
    }

    @Test
    public void draftTitle_should_keep_existing_prefix() {
        assertEquals("Draft: feat: xxx", policy.draftTitle("Draft: feat: xxx"));
        // GitLab 对 draft 前缀大小写不敏感,已带小写前缀不再叠加
        assertEquals("draft: feat: xxx", policy.draftTitle("draft: feat: xxx"));
    }

    @Test
    public void draftTitle_should_require_title() {
        assertThrows(IllegalArgumentException.class, () -> policy.draftTitle(" "));
        assertThrows(IllegalArgumentException.class, () -> policy.draftTitle(null));
    }
}
