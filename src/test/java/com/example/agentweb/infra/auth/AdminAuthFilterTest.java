package com.example.agentweb.infra.auth;

import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.UserContext;
import com.example.agentweb.domain.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 管理台数据接口角色闸门过滤器单测:多前缀路径匹配 + ADMIN 角色的放行/拦截分支。
 *
 * <p>直接使用 {@link AdminProperties} 代码默认值，防止 profile/YAML 漏配导致管理能力 fail-open。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAuthFilterTest {

    private LoginUser currentUser;
    private final UserContext userContext = () -> Optional.ofNullable(currentUser);
    private final AdminAuthFilter filter = new AdminAuthFilter(userContext, new AdminProperties());

    @Test
    public void protectedPath_withoutBoundUser_failsClosed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/metrics/overview");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/chat/list", "/api/auth/login", "/", "/api/fs/roots", "/api/metrics-internal"})
    public void nonProtectedPath_passesThrough(String uri) throws Exception {
        MockFilterChain chain = doFilter(uri);

        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/metrics/overview",
            "/api/diagnose-history/T-1",
            "/api/issue-log-backfill/candidates",
            "/api/admin-user-suggestions",
            "/api/admin-workflows",
            "/api/admin-workflow-executions/exec-1",
            "/api/admin-settings",
            "/api/refinery/rebuild-recent",
    })
    public void protectedPath_noCookie_returns401(String uri) throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = doFilter(uri, resp);

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
            "/api/admin-settings",
            "/api/refinery/rebuild-recent",
    })
    public void protectedPath_normalUser_returns403(String uri) throws Exception {
        currentUser = new LoginUser("user", "user", null, UserRole.USER);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MockFilterChain chain = doFilter(uri, resp);

        assertEquals(403, resp.getStatus());
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
            "/api/admin-settings",
            "/api/refinery/rebuild-recent",
    })
    public void protectedPath_adminUser_passesThrough(String uri) throws Exception {
        currentUser = new LoginUser("admin", "admin", null, UserRole.ADMIN);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        MockFilterChain chain = doFilter(uri, resp);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    private MockFilterChain doFilter(String uri) throws Exception {
        return doFilter(uri, new MockHttpServletResponse());
    }

    private MockFilterChain doFilter(String uri, MockHttpServletResponse resp) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return chain;
    }
}
