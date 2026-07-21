package com.example.agentweb.config;

import jakarta.servlet.ServletException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

import java.io.IOException;

/**
 * 把裸根 {@code /} 重定向到应用挂载前缀(如 {@code /qa/})。
 *
 * <p>应用整体挂载在 {@code server.servlet.context-path}(当前 {@code /qa})上,直连 IP 访问裸根
 * {@code http://ip:18092/} 落在 Tomcat 不存在的 ROOT context → 404/空白,且**根本进不到 Spring**
 * (DispatcherServlet、{@code @Controller}、过滤器全在 {@code /qa} context 内,拦不到 context 外的请求)。</p>
 *
 * <p>因此必须在 context 路由**之前**拦截:Engine 级 Valve 对所有请求生效(含未匹配到 context 的),
 * 在 {@code StandardEngineValve} 因无 host/context 返回 404 之前抢先把 {@code /} 302 到 {@code <prefix>/}。
 * 仅命中裸根,其余路径(含 {@code /qa/...})一律 {@code getNext()} 原样放行,不干扰正常路由。</p>
 *
 * <p>根部署(context-path 为空)时 target 退化为 {@code /},Valve 自动空转不重定向。</p>
 *
 * @author zhourui(V33215020)
 */
public class RootRedirectValve extends ValveBase {

    /** 重定向目标,形如 {@code /qa/};根部署时为 {@code /}(等价不重定向)。 */
    private final String target;

    public RootRedirectValve(String contextPath) {
        super(true);
        this.target = normalize(contextPath);
    }

    static String normalize(String contextPath) {
        if (contextPath == null || contextPath.isEmpty() || "/".equals(contextPath)) {
            return "/";
        }
        String value = contextPath.startsWith("/") ? contextPath : "/" + contextPath;
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value + "/";
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        String uri = request.getRequestURI();
        if (!"/".equals(target) && (uri == null || uri.isEmpty() || "/".equals(uri))) {
            response.sendRedirect(target);
            return;
        }
        getNext().invoke(request, response);
    }
}
