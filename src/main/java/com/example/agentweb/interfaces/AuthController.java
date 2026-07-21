package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.LoginUrlBuilder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 本地登录、登录态查询与注销 HTTP 接口。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties authProperties;
    private final AuthAppService authAppService;

    public AuthController(AuthProperties authProperties, AuthAppService authAppService) {
        this.authProperties = authProperties;
        this.authAppService = authAppService;
    }

    /**
     * 使用工号和用户名创建本地登录会话。
     *
     * @param request 登录请求
     * @param response HTTP 响应
     * @return 登录结果
     */
    @PostMapping("/manual-login")
    public Map<String, Object> manualLogin(@RequestBody ManualLoginRequest request,
                                           HttpServletResponse response) {
        ManualSession session = authAppService.manualLogin(request.getEmployeeId(), request.getUserName());
        writeSessionCookie(response, session.getSessionId(), sessionCookieMaxAge());

        Map<String, Object> result = new HashMap<>(8);
        result.put("success", true);
        result.put("userId", session.getUserId());
        result.put("userName", session.getUserName());
        return result;
    }

    /**
     * 注销当前本地登录会话。
     *
     * @param request HTTP 请求
     * @param response HTTP 响应
     * @return 注销结果和登录页地址
     */
    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response) {
        authAppService.logout(readSessionCookie(request));
        clearSessionCookie(response);

        Map<String, Object> result = new HashMap<>(8);
        result.put("loginUrl", LoginUrlBuilder.loginPage(request, authProperties));
        result.put("success", true);
        return result;
    }

    /**
     * 查询当前本地登录状态。
     *
     * @param request HTTP 请求
     * @return 登录状态
     */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Optional<LoginUser> user = authAppService.resolveUser(readSessionCookie(request));
        Map<String, Object> result = new HashMap<>(16);
        result.put("mode", "manual");
        result.put("enforced", true);
        result.put("authEnabled", true);
        result.put("authenticated", user.isPresent());
        if (user.isPresent()) {
            result.put("userId", user.get().getUserId());
            result.put("username", user.get().getUserName());
        } else {
            result.put("loginUrl", LoginUrlBuilder.loginPage(request, authProperties));
        }
        return result;
    }

    private int sessionCookieMaxAge() {
        return (int) Math.min(authProperties.getSessionTtlSeconds(), Integer.MAX_VALUE);
    }

    private String readSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authProperties.getCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeSessionCookie(HttpServletResponse response, String value, int maxAge) {
        Cookie cookie = new Cookie(authProperties.getCookieName(), value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        response.addCookie(cookie);
    }

    private void clearSessionCookie(HttpServletResponse response) {
        writeSessionCookie(response, "", 0);
    }

    /**
     * 工号登录请求。
     *
     * @author zhourui(V33215020)
     * @since 2026-07-17
     */
    @Data
    public static class ManualLoginRequest {
        private String employeeId;
        private String userName;
    }
}
