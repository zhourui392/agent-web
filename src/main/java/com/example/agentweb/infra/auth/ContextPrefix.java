package com.example.agentweb.infra.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 应用挂载前缀的单一真相源:从 servlet {@code contextPath}
 * ({@code server.servlet.context-path},当前为 {@code /qa})派生。
 *
 * <p>「共享域名 + /qa 路径前缀」部署下,应用整体挂载在 {@code /qa} 上下文,网关原样透传、
 * 不剥前缀。所有入口(域名 / 直连 IP / 本地直跑)看到同一套带前缀路径,不再依赖
 * {@code X-Forwarded-Prefix} header 协议。Tomcat 对恰为 {@code /qa}(无尾斜杠)的请求
 * 自动 302 补成 {@code /qa/},根治「无尾斜杠时页面相对资源解析到根路径全 404」。</p>
 *
 * <p>切到独立域名时:把 {@code context-path} 改回根,所有派生点自动回到空前缀,Java 代码零改动。</p>
 *
 * @author zhourui(V33215020)
 */
public final class ContextPrefix {

    private ContextPrefix() {
        // util
    }

    /**
     * 当前应用挂载前缀。
     *
     * <p>Servlet 规范保证 {@code contextPath} 要么为空(根部署),要么以 {@code /} 打头且无尾斜杠,
     * 此处仍做防御性归一,容器实现差异不外泄。</p>
     *
     * @param request 当前请求
     * @return 形如 {@code "/qa"} 的前缀(无尾斜杠);根部署时返回 {@code ""}
     */
    public static String of(HttpServletRequest request) {
        String value = request.getContextPath();
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        // 去尾斜杠,避免拼接出 //;根 "/" 归一为无前缀
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return "/".equals(value) ? "" : value;
    }

    /**
     * 返回去掉挂载前缀后的「逻辑路径」(根相对)。
     *
     * <p>context-path 部署下 {@code requestURI} 含前缀({@code /qa/login.html}),公开路径判定
     * ({@code PublicPaths})与 302/回跳 URL 拼接都基于逻辑路径,统一在此剥前缀。网关转发不规范时
     * (多余斜杠,如 {@code /qa//login.html} / {@code //login.html})先 collapse 连续斜杠再剥,
     * 让精确匹配({@code /login.html})对脏 URL 幂等,杜绝登录页失配 {@code PublicPaths} 被当
     * 未登录、把自身再套进 {@code redirect} 的无限重定向。</p>
     *
     * @param request 当前请求
     * @return 去掉前缀、归一斜杠后的根相对路径;恰为前缀本身时返回 {@code "/"};不以前缀打头时返回归一后的 {@code requestURI}
     */
    public static String strip(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        if (uri.indexOf("//") >= 0) {
            uri = uri.replaceAll("/{2,}", "/");
        }
        String prefix = of(request);
        if (prefix.isEmpty()) {
            return uri;
        }
        if (uri.equals(prefix)) {
            return "/";
        }
        // 必须是 "prefix/..." 才算真前缀,避免 prefix=/qa 误剥 /qabc
        if (uri.startsWith(prefix + "/")) {
            return uri.substring(prefix.length());
        }
        return uri;
    }
}
