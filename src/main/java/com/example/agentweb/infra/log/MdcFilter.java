package com.example.agentweb.infra.log;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
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
import java.util.UUID;

/**
 * 为每个 HTTP 请求注入 MDC traceId。
 * <p>优先复用上游 X-Trace-Id Header；否则随机生成短 8 位 UUID。
 * traceId 同时回写到响应 Header，便于前端/排障定位。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026/05/19
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcFilter implements Filter {

    /** MDC key：贯穿一次请求链路的追踪 ID。 */
    public static final String MDC_TRACE_ID = "traceId";

    /** MDC key：聊天会话 ID（由 ChatController 在拿到 sessionId 后补写）。 */
    public static final String MDC_SESSION_ID = "sessionId";

    /** MDC key：诊断任务 ID。 */
    public static final String MDC_TASK_ID = "taskId";

    /** MDC key：当前登录用户 ID（由 SessionAuthFilter 校验通过后写入）。 */
    public static final String MDC_USER_ID = "userId";

    private static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final int TRACE_ID_LENGTH = 8;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = extractOrGenerate(request);
        try {
            MDC.put(MDC_TRACE_ID, traceId);
            if (response instanceof HttpServletResponse) {
                ((HttpServletResponse) response).setHeader(HEADER_TRACE_ID, traceId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SESSION_ID);
            MDC.remove(MDC_TASK_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    private String extractOrGenerate(ServletRequest request) {
        if (request instanceof HttpServletRequest) {
            String header = ((HttpServletRequest) request).getHeader(HEADER_TRACE_ID);
            if (header != null && !header.trim().isEmpty()) {
                return header.trim();
            }
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
    }
}
