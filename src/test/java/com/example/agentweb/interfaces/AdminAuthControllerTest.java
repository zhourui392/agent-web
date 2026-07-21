package com.example.agentweb.interfaces;

import com.example.agentweb.infra.auth.AdminAccessService;
import com.example.agentweb.infra.auth.AdminProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理台登录端点单测:登录成功/失败分支 + httpOnly cookie 种植。直接调用,不起容器。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
public class AdminAuthControllerTest {

    private final AdminAccessService accessService = mock(AdminAccessService.class);
    private final AdminAuthController controller = new AdminAuthController(accessService, props());

    @Test
    public void login_success_returns200AndSetsHttpOnlyCookie() {
        when(accessService.login("s3cret")).thenReturn(Optional.of("tok-123"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> result =
                controller.login(Collections.singletonMap("password", "s3cret"), resp);

        assertEquals(200, result.getStatusCodeValue());
        assertEquals(Boolean.TRUE, result.getBody().get("authenticated"));
        Cookie cookie = resp.getCookie("admin_session");
        assertNotNull(cookie);
        assertEquals("tok-123", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
    }

    @Test
    public void login_failure_returns401NoCookie() {
        when(accessService.login("wrong")).thenReturn(Optional.empty());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<Map<String, Object>> result =
                controller.login(Collections.singletonMap("password", "wrong"), resp);

        assertEquals(401, result.getStatusCodeValue());
        assertEquals(Boolean.FALSE, result.getBody().get("authenticated"));
    }

    @Test
    public void logout_invalidatesTokenAndExpiresCookie() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("admin_session", "tok-123"));
        MockHttpServletResponse resp = new MockHttpServletResponse();

        controller.logout(req, resp);

        verify(accessService).logout("tok-123");
        Cookie cookie = resp.getCookie("admin_session");
        assertNotNull(cookie);
        assertEquals(0, cookie.getMaxAge());
    }

    @Test
    public void status_reflectsAuthState() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setCookies(new Cookie("admin_session", "tok-123"));
        when(accessService.isAuthenticated("tok-123")).thenReturn(true);

        ResponseEntity<Map<String, Object>> result = controller.status(req);

        assertEquals(Boolean.TRUE, result.getBody().get("authenticated"));
        assertEquals(Boolean.TRUE, result.getBody().get("authEnabled"));
    }

    @Test
    public void status_whenDisabled_returnsAuthenticatedWithoutCookie() {
        AdminProperties props = props();
        props.setEnabled(false);
        AdminAuthController disabledController = new AdminAuthController(accessService, props);

        ResponseEntity<Map<String, Object>> result = disabledController.status(new MockHttpServletRequest());

        assertEquals(200, result.getStatusCodeValue());
        assertEquals(Boolean.TRUE, result.getBody().get("authenticated"));
        assertEquals(Boolean.FALSE, result.getBody().get("authEnabled"));
    }

    private AdminProperties props() {
        AdminProperties p = new AdminProperties();
        p.setPassword("s3cret");
        p.setCookieName("admin_session");
        p.setSessionTtlMinutes(480L);
        return p;
    }
}
