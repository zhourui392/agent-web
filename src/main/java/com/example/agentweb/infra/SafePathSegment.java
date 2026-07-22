package com.example.agentweb.infra;

import java.util.regex.Pattern;

/**
 * 用于上传子目录的安全单路径段校验。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
final class SafePathSegment {

    private static final Pattern SESSION_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    private SafePathSegment() {
    }

    static String optionalSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        String normalized = sessionId.trim();
        if (!SESSION_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("sessionId 格式非法");
        }
        return normalized;
    }
}
