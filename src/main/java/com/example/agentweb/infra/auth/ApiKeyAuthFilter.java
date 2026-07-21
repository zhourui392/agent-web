package com.example.agentweb.infra.auth;

import com.example.agentweb.infra.log.LogSafe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 仅对外部建需求入口 {@code /api/requirements/external} 生效的 API Key 认证过滤器。
 *
 * <p>合法 key 通过后将 {@code apiKeyName} 写入 request attribute，供 Controller/AppService 取用，
 * 用于审计与幂等键命名空间。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-09
 */
@Component
@Order(0)
@Slf4j
public class ApiKeyAuthFilter implements Filter {

    public static final String HEADER_API_KEY = "X-API-Key";
    public static final String ATTR_API_KEY_NAME = "api.apiKeyName";

    /** 外部系统建需求入口（M2）复用统一的 API Key 体系与幂等键命名空间。 */
    private static final String REQUIREMENT_EXTERNAL_PATH = "/api/requirements/external";

    private final ApiKeyProperties props;

    public ApiKeyAuthFilter(ApiKeyProperties props) {
        this.props = props;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        // context-path 挂载部署(/qa)下 requestURI 含挂载前缀,剥成逻辑路径再做匹配
        String path = ContextPrefix.strip(request);

        if (!isProtectedPath(path)) {
            chain.doFilter(req, res);
            return;
        }

        String clientIp = request.getRemoteAddr();
        String key = request.getHeader(HEADER_API_KEY);
        if (key == null || key.trim().isEmpty()) {
            log.warn("api-auth-rejected reason=missing-header path={} clientIp={}", path, clientIp);
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"missing X-API-Key\"}");
            return;
        }

        ApiKeyProperties.ApiKeyEntry entry = props.findByKey(key.trim());
        if (entry == null) {
            log.warn("api-auth-rejected reason=invalid-key path={} clientIp={} keyMask={}",
                    path, clientIp, LogSafe.maskKey(key));
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"invalid api key\"}");
            return;
        }

        request.setAttribute(ATTR_API_KEY_NAME, entry.getName());
        log.debug("api-auth-ok keyName={} path={} clientIp={}", entry.getName(), path, clientIp);
        chain.doFilter(req, res);
    }

    private boolean isProtectedPath(String path) {
        return REQUIREMENT_EXTERNAL_PATH.equals(path);
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
