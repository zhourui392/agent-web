package com.example.agentweb.infra.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求路径归一:把 {@code requestURI} 里的连续斜杠 collapse 成单个。
 *
 * <p>取代原 {@code ContextPrefix}。应用已固定挂在域名根路径
 * ({@code server.servlet.context-path} 为空),不再有 {@code /qa} 挂载前缀要剥,
 * 但原先与剥前缀捆在一起的**斜杠归一必须保留**:网关转发不规范会产生
 * {@code //login.html} 这类脏 URL,若不归一就会失配 {@link PublicPaths} 的精确匹配,
 * 登录页被当成未登录页面、再把自身套进 {@code redirect} 参数,形成无限重定向。</p>
 *
 * @author zhourui(V33215020)
 */
public final class RequestPath {

    private RequestPath() {
        // util
    }

    /**
     * 返回归一斜杠后的请求路径。
     *
     * @param request 当前请求
     * @return collapse 连续斜杠后的 {@code requestURI};{@code requestURI} 为 null 时返回 null
     */
    public static String normalized(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        return uri.indexOf("//") >= 0 ? uri.replaceAll("/{2,}", "/") : uri;
    }
}
