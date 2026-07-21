package com.example.agentweb.infra.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 管理台数据接口口令闸门过滤器单测:多前缀路径匹配 + 令牌校验的放行/拦截分支。
 *
 * <p>受保护前缀集合来自 {@link AdminProperties#getProtectedPrefixes()};本测试配置三前缀
 * (metrics / diagnose-history / issue-log-backfill / admin-user-suggestions / workflow),
 * 验证每个前缀的 401/放行行为,以及登录端点与非保护路径直通。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAuthFilterTest {

    private final AdminAccessService accessService = mock(AdminAccessService.class);
    private final AdminAuthFilter filter = new AdminAuthFilter(accessService, props());

    @Test
    public void disabled_passesThroughWithoutAuthCheck() throws Exception {
        AdminProperties props = props();
        props.setEnabled(false);
        AdminAuthFilter disabledFilter = new AdminAuthFilter(accessService, props);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/metrics/overview");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        disabledFilter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
        verifyNoInteractions(accessService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/list", "/api/admin/login", "/", "/api/fs/roots"})
    public void nonProtectedPath_passesThroughWithoutAuthCheck(String uri) throws Exception {
        MockFilterChain chain = doFilter(uri, null);

        assertNotNull(chain.getRequest());
        verifyNoInteractions(accessService);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/metrics/overview",
            "/api/diagnose-history/T-1",
            "/api/issue-log-backfill/candidates",
            "/api/admin-user-suggestions",
            "/api/admin-workflows",
            "/api/admin-workflow-executions/exec-1",
    })
    public void protectedPath_noCookie_returns401(String uri) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = doFilter(uri, null, resp);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/metrics/overview",
            "/api/diagnose-history/T-1",
            "/api/issue-log-backfill/candidates",
            "/api/admin-user-suggestions",
            "/api/admin-workflows",
            "/api/admin-workflow-executions/exec-1",
    })
    public void protectedPath_invalidToken_returns401(String uri) throws Exception {
        when(accessService.isAuthenticated("bad")).thenReturn(false);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MockFilterChain chain = doFilter(uri, "bad", resp);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/metrics/overview",
            "/api/diagnose-history/T-1",
            "/api/issue-log-backfill/candidates",
            "/api/admin-user-suggestions",
            "/api/admin-workflows",
            "/api/admin-workflow-executions/exec-1",
    })
    public void protectedPath_validToken_passesThrough(String uri) throws Exception {
        when(accessService.isAuthenticated("good")).thenReturn(true);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MockFilterChain chain = doFilter(uri, "good", resp);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    private MockFilterChain doFilter(String uri, String token) throws Exception {
        return doFilter(uri, token, new MockHttpServletResponse());
    }

    private MockFilterChain doFilter(String uri, String token, MockHttpServletResponse resp) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        if (token != null) {
            req.setCookies(new Cookie("admin_session", token));
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return chain;
    }

    private AdminProperties props() {
        AdminProperties p = new AdminProperties();
        p.setPassword("x");
        p.setCookieName("admin_session");
        p.setProtectedPrefixes(Arrays.asList(
                "/api/metrics", "/api/diagnose-history", "/api/issue-log-backfill",
                "/api/admin-user-suggestions", "/api/admin-workflows",
                "/api/admin-workflow-executions"));
        return p;
    }
}
