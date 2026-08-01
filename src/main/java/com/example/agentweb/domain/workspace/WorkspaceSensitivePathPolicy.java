package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.DomainText;

import java.util.Locale;

/**
 * Workspace 文件查看与变更证据共享的敏感相对路径策略。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class WorkspaceSensitivePathPolicy {

    private WorkspaceSensitivePathPolicy() {
    }

    public static boolean isSensitive(String value) {
        String normalized = DomainText.require(
                        value, "workspace relative path", 4096)
                .replace('\\', '/').toLowerCase(Locale.ROOT);
        String[] segments = normalized.split("/", -1);
        for (String segment : segments) {
            if (isSensitiveSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveSegment(String segment) {
        return ".env".equals(segment)
                || segment.startsWith(".env.")
                || "env.local".equals(segment)
                || "data".equals(segment)
                || ".codex".equals(segment)
                || ".claude".equals(segment)
                || ".git".equals(segment)
                || segment.contains("secrets.properties")
                || segment.contains(".pem")
                || segment.contains(".key");
    }
}
