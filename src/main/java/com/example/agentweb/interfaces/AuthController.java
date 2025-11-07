package com.example.agentweb.interfaces;

import com.example.agentweb.interfaces.dto.LoginRequest;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping(path = "/api/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class AuthController {

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "Zz135246!@#$";
    private static final String SESSION_USER_KEY = "user";

    @PostMapping("/login")
    public Map<String, Object> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
        if (!ADMIN_USERNAME.equals(request.getUsername())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        if (!ADMIN_PASSWORD.equals(request.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        // 登录成功，设置session
        session.setAttribute(SESSION_USER_KEY, ADMIN_USERNAME);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("username", ADMIN_USERNAME);
        return result;
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        return result;
    }

    @GetMapping("/check")
    public Map<String, Object> check(HttpSession session) {
        Object user = session.getAttribute(SESSION_USER_KEY);
        Map<String, Object> result = new HashMap<>();
        result.put("loggedIn", user != null);
        if (user != null) {
            result.put("username", user);
        }
        return result;
    }
}
