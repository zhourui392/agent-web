package com.example.agentweb.interfaces;

import com.example.agentweb.infra.auth.ContextPrefix;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;

/**
 * 管理后台入口重定向:{@code /admin} 与 {@code /admin/} → {@code <prefix>/admin/dashboard.html}。
 *
 * <p>不能用 {@code WebConfig.addRedirectViewController} 静态重定向:共享域名 /qa 部署下,这个 302
 * Location 由浏览器直接跟随(不经前端 JS),目标必须补回 {@link ContextPrefix} 挂载前缀
 * ({@code sendRedirect} 的根相对路径不会自动加 contextPath)。静态配置补不了前缀,会 302 到丢了 /qa 的
 * {@code /admin/dashboard.html},浏览器解析到根域 → 网关无 /admin location → 进不去管理页。
 * 改用 Controller 在运行时按 contextPath 补前缀。</p>
 *
 * @author zhourui(V33215020)
 */
@Controller
public class AdminEntryController {

    private static final String DASHBOARD = "/admin/dashboard.html";

    @GetMapping({"/admin", "/admin/"})
    public void entry(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.sendRedirect(ContextPrefix.of(request) + DASHBOARD);
    }
}
