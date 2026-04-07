package com.example.agentweb.interfaces;

import com.example.agentweb.infra.AuthFilter;
import com.example.agentweb.infra.AuthProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthProperties authProperties;

    /** Tracks consecutive failed login attempts (reset on success). */
    private final AtomicInteger failCount = new AtomicInteger(0);

    /** Once locked, stays locked until application restart. */
    private volatile boolean locked = false;

    public AuthController(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();

        if (locked) {
            result.put("success", false);
            result.put("message", "账号已锁定，请联系管理员或重启服务");
            return result;
        }

        if (authProperties.getUsername().equals(body.getUsername())
                && authProperties.getPassword().equals(body.getPassword())) {
            failCount.set(0);
            HttpSession session = request.getSession(true);
            session.setAttribute(AuthFilter.SESSION_ATTR_USER, body.getUsername());
            result.put("success", true);
        } else {
            int fails = failCount.incrementAndGet();
            int remaining = authProperties.getMaxFailCount() - fails;
            if (remaining <= 0) {
                locked = true;
                result.put("success", false);
                result.put("message", "账号已锁定，请联系管理员或重启服务");
            } else {
                result.put("success", false);
                result.put("message", "用户名或密码错误，还剩 " + remaining + " 次机会");
            }
        }
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("authEnabled", authProperties.isEnabled());
        HttpSession session = request.getSession(false);
        result.put("authenticated", session != null && session.getAttribute(AuthFilter.SESSION_ATTR_USER) != null);
        return result;
    }

    public static class LoginRequest {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
