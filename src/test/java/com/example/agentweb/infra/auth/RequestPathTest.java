package com.example.agentweb.infra.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link RequestPath} 的斜杠归一契约。
 *
 * <p>承接原 {@code ContextPrefixTest} 中与挂载前缀无关的那部分用例:{@code /qa} 挂载前缀已废弃,
 * 但脏 URL 归一仍是安全要求 —— 不归一会让 {@code //login.html} 失配 {@link PublicPaths},
 * 登录页被当未登录页面、把自身套进 redirect 形成无限重定向。</p>
 *
 * @author zhourui(V33215020)
 */
class RequestPathTest {

    private static HttpServletRequest req(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("常规路径原样返回")
    void normalPath_unchanged() {
        assertEquals("/login.html", RequestPath.normalized(req("/login.html")));
        assertEquals("/api/chat/sessions", RequestPath.normalized(req("/api/chat/sessions")));
        assertEquals("/", RequestPath.normalized(req("/")));
    }

    @Test
    @DisplayName("网关转发产生的连续斜杠 collapse 成单个")
    void duplicateSlashes_collapsed() {
        assertEquals("/login.html", RequestPath.normalized(req("//login.html")));
        assertEquals("/login.html", RequestPath.normalized(req("///login.html")));
        assertEquals("/admin/dashboard.html", RequestPath.normalized(req("/admin//dashboard.html")));
    }

    @Test
    @DisplayName("requestURI 为 null 时返回 null,不抛异常")
    void nullUri_returnsNull() {
        assertNull(RequestPath.normalized(req(null)));
    }
}
