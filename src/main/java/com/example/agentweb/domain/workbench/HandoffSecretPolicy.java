package com.example.agentweb.domain.workbench;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Handoff 人工文本的轻量 Secret-like 内容门禁。
 *
 * <p>只识别凭据赋值、Bearer、常见 Key 前缀和 PEM 私钥头；普通密码策略、
 * Token Bucket、Key 管理等描述不会仅因关键词出现而被拒绝。</p>
 *
 * @author alex
 * @since 2026-08-01
 */
final class HandoffSecretPolicy {

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

    private HandoffSecretPolicy() {
    }

    static void requireSafe(
            String summary, List<Decision> decisions,
            List<OpenQuestion> questions,
            List<WorkbenchRunReference> runs) {
        requireSafe(summary);
        for (Decision decision : decisions) {
            requireSafe(decision.getText());
            requireSafe(decision.getRationale());
        }
        for (OpenQuestion question : questions) {
            requireSafe(question.getText());
            requireSafe(question.getOwnerHint());
        }
        for (WorkbenchRunReference run : runs) {
            requireSafe(run.getSafeSummary());
        }
    }

    private static void requireSafe(String value) {
        if (value != null && isSecretLike(value)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.HANDOFF_SECRET_DETECTED,
                    "handoff content contains secret-like material");
        }
    }

    private static boolean isSecretLike(String value) {
        return ASSIGNMENT.matcher(value).find()
                || BEARER.matcher(value).find()
                || PRIVATE_KEY.matcher(value).find()
                || COMMON_KEY.matcher(value).find();
    }
}
