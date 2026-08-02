package com.example.agentweb.domain.workbench;

import java.util.regex.Pattern;

/**
 * 高影响操作人工安全预览的 Credential-like 内容门禁。
 *
 * @author alex
 * @since 2026-08-01
 */
final class HighImpactOperationSecretPolicy {

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[ _-]?key|access[ _-]?token|auth[ _-]?token|"
                    + "token|password|passwd|client[ _-]?secret)\\b"
                    + "\\s*[=:]\\s*[\\\"']?[^\\s\\\"']{4,}");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\b(?:authorization\\s*[=:]\\s*)?bearer"
                    + "\\s+[A-Za-z0-9._~+/=-]{12,}");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?i)-----BEGIN\\s+(?:[A-Z0-9]+\\s+)*PRIVATE KEY-----");
    private static final Pattern COMMON_KEY = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{12,}|"
                    + "gh[pousr]_[A-Za-z0-9]{20,}|"
                    + "github_pat_[A-Za-z0-9_]{20,}|"
                    + "AKIA[0-9A-Z]{16})\\b");

    private HighImpactOperationSecretPolicy() {
    }

    static void requireSafePreview(String value) {
        if (value != null && (ASSIGNMENT.matcher(value).find()
                || BEARER.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()
                || COMMON_KEY.matcher(value).find())) {
            throw new IllegalArgumentException(
                    "operation preview contains secret-like material");
        }
    }
}
