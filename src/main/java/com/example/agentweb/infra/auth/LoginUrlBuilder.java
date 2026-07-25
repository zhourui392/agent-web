package com.example.agentweb.infra.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 本地登录页 URL 构造器，统一处理登录后回跳地址。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-17
 */
public final class LoginUrlBuilder {

    private static final String LOGIN_PAGE = "/login.html";

    private LoginUrlBuilder() {
    }

    /**
     * 构造登录页地址。
     *
     * @param request 当前请求
     * @param properties 登录配置
     * @return 带安全回跳参数的登录页地址
     */
    public static String loginPage(HttpServletRequest request, AuthProperties properties) {
        String redirect = "redirect=" + encode(resolveRedirectTarget(request));
        String configured = properties == null ? null : properties.getLoginPageUrl();
        if (configured != null && !configured.trim().isEmpty()) {
            String base = configured.trim();
            return base + (base.indexOf('?') > -1 ? '&' : '?') + redirect;
        }
        return LOGIN_PAGE + "?" + redirect;
    }

    private static String resolveRedirectTarget(HttpServletRequest request) {
        // 归一斜杠: 脏 URL 直接进 redirect 会让登录页失配 PublicPaths, 造成无限重定向
        String path = RequestPath.normalized(request);
        if (path == null || PublicPaths.isApiPath(path)) {
            // API 路径浏览器 GET 过去只会 405 / 裸 JSON, 回跳首页
            return "/";
        }
        String query = request.getQueryString();
        return query == null || query.isEmpty() ? path : path + "?" + query;
    }

    private static String encode(String raw) {
        try {
            return URLEncoder.encode(raw, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return raw;
        }
    }
}
