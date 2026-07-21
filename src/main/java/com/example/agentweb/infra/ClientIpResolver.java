package com.example.agentweb.infra;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 HTTP 请求解析客户端来源 IP，供审计归因落库。
 * <p>优先级：{@code X-Forwarded-For}（取最初客户端那一段）→ {@code X-Real-IP} → {@code getRemoteAddr()}。
 * 在直连与反向代理后面两种部署下都能拿到合理值；无需开启全局 {@code forward-headers-strategy}。</p>
 * <p><b>注意</b>：非可信代理链路下 {@code X-Forwarded-For} 可被客户端伪造，故此值仅作弱审计线索，不用于鉴权。</p>
 *
 * @author zhourui(V33215020)
 * @since 2026/06/03
 */
public final class ClientIpResolver {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String FORWARDED_FOR_DELIMITER = ",";

    private ClientIpResolver() {
    }

    /**
     * 解析请求的客户端 IP。
     *
     * @param request 当前 HTTP 请求
     * @return 客户端 IP；代理头缺失时回退到 socket 远端地址
     */
    public static String resolve(HttpServletRequest request) {
        String firstHop = firstForwardedHop(request.getHeader(HEADER_FORWARDED_FOR));
        if (firstHop != null) {
            return firstHop;
        }
        String realIp = trimToNull(request.getHeader(HEADER_REAL_IP));
        if (realIp != null) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /** 取 {@code X-Forwarded-For} 链路中最初客户端那一段；整体空白/缺失返回 null。 */
    private static String firstForwardedHop(String forwardedFor) {
        String normalized = trimToNull(forwardedFor);
        if (normalized == null) {
            return null;
        }
        return trimToNull(normalized.split(FORWARDED_FOR_DELIMITER)[0]);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
