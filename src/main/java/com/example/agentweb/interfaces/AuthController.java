package com.example.agentweb.interfaces;

import com.example.agentweb.app.auth.AuthAppService;
import com.example.agentweb.domain.auth.LoginUser;
import com.example.agentweb.domain.auth.ManualSession;
import com.example.agentweb.infra.auth.AuthProperties;
import com.example.agentweb.infra.auth.LoginAttemptLimiter;
import com.example.agentweb.infra.auth.LoginUrlBuilder;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
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
    private final LoginAttemptLimiter loginAttemptLimiter;

    public AuthController(AuthProperties authProperties,
                          AuthAppService authAppService,
                          LoginAttemptLimiter loginAttemptLimiter) {
        this.authProperties = authProperties;
        this.authAppService = authAppService;
        this.loginAttemptLimiter = loginAttemptLimiter;
    }

    /**
     * 使用用户名和密码创建本地登录会话。
     *
     * @param request 登录请求
     * @param response HTTP 响应
     * @return 登录结果
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse response) {
        String remoteAddress = httpRequest.getRemoteAddr();
        long retryAfter = loginAttemptLimiter.retryAfterSeconds(remoteAddress, request.getUsername());
        if (retryAfter > 0L) {
            Map<String, Object> failure = new HashMap<>(2);
            failure.put("error", "登录尝试过于频繁，请稍后重试");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .body(failure);
        }
        Optional<ManualSession> authenticated = authAppService.login(request.getUsername(), request.getPassword());
        if (!authenticated.isPresent()) {
            loginAttemptLimiter.recordFailure(remoteAddress, request.getUsername());
            Map<String, Object> failure = new HashMap<>(2);
            failure.put("error", "用户名或密码错误");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(failure);
        }
        loginAttemptLimiter.recordSuccess(remoteAddress, request.getUsername());
        ManualSession session = authenticated.get();
        writeSessionCookie(response, session.getSessionId(), sessionCookieMaxAge(), isSecure(httpRequest));

        Map<String, Object> result = new HashMap<>(8);
        result.put("success", true);
        result.put("userId", session.getUserId());
        result.put("userName", session.getUserName());
        return ResponseEntity.ok(result);
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
        clearSessionCookie(response, isSecure(request));

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
            result.put("role", user.get().getRole().name());
        } else {
            result.put("loginUrl", LoginUrlBuilder.loginPage(request, authProperties));
        }
        return result;
    }

    private int sessionCookieMaxAge() {
        return (int) Math.min(authProperties.getSessionTtlSeconds(), Integer.MAX_VALUE);
    }

    private boolean isSecure(HttpServletRequest request) {
        return authProperties.isCookieSecure() || request.isSecure();
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

    private void writeSessionCookie(HttpServletResponse response, String value, int maxAge, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(authProperties.getCookieName(), value)
                .path("/")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearSessionCookie(HttpServletResponse response, boolean secure) {
        writeSessionCookie(response, "", 0, secure);
    }

    /**
     * 用户名密码登录请求。
     *
     * @author zhourui(V33215020)
     * @since 2026-07-17
     */
    @Data
    public static class LoginRequest {
        @NotBlank
        @Size(max = 64)
        private String username;

        @NotBlank
        @Size(max = 256)
        private String password;
    }
}
