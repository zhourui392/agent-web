package com.example.agentweb.interfaces;

import com.example.agentweb.infra.auth.AdminAccessService;
import com.example.agentweb.infra.auth.AdminProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理后台口令登录端点。不被 {@link com.example.agentweb.infra.auth.AdminAuthFilter} 拦截,
 * 供登录前调用;登录成功种 httpOnly 会话 cookie,后续 {@code /api/metrics/**} 凭此放行。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-07
 */
@RestController
@RequestMapping(path = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminAuthController {

    private static final int SECONDS_PER_MINUTE = 60;

    private final AdminAccessService accessService;
    private final AdminProperties properties;

    public AdminAuthController(AdminAccessService accessService, AdminProperties properties) {
        this.accessService = accessService;
        this.properties = properties;
    }

    /**
     * 口令登录。成功 200 + 种 cookie;失败 401。
     *
     * @param body 请求体,含 {@code password} 字段
     * @param resp 用于种会话 cookie
     * @return 登录结果 {@code {authenticated: bool}}
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body,
                                                     HttpServletResponse resp) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(statusBody(true));
        }
        String password = body == null ? null : body.get("password");
        Optional<String> token = accessService.login(password);
        if (!token.isPresent()) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED)
                    .body(statusBody(false));
        }
        addSessionCookie(resp, token.get(), (int) (properties.getSessionTtlMinutes() * SECONDS_PER_MINUTE));
        return ResponseEntity.ok(statusBody(true));
    }

    /**
     * 注销:清服务端令牌 + 过期 cookie。
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req, HttpServletResponse resp) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(statusBody(true));
        }
        accessService.logout(readCookie(req, properties.getCookieName()));
        addSessionCookie(resp, "", 0);
        return ResponseEntity.ok(statusBody(false));
    }

    /**
     * 当前是否已通过口令鉴权,供前端决定显示登录框还是看板。
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
        if (!properties.isEnabled()) {
            return ResponseEntity.ok(statusBody(true));
        }
        boolean authenticated = accessService.isAuthenticated(readCookie(req, properties.getCookieName()));
        return ResponseEntity.ok(statusBody(authenticated));
    }

    private Map<String, Object> statusBody(boolean authenticated) {
        Map<String, Object> body = new HashMap<>(2);
        body.put("authenticated", authenticated);
        body.put("authEnabled", properties.isEnabled());
        return body;
    }

    private void addSessionCookie(HttpServletResponse resp, String value, int maxAgeSeconds) {
        Cookie cookie = new Cookie(properties.getCookieName(), value);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        resp.addCookie(cookie);
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
