package com.example.agentweb.domain.issuelog;

import lombok.Getter;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * issue-log 聚合根。包装 id + 草稿正文 + 落盘相对路径 + 创建时间,
 * 表示一份已经在工作目录中具备物理存在的 issue 记录。
 *
 * <p>不变量:</p>
 * <ul>
 *   <li>{@code id} 形如 {@code I-\d+}(至少 1 位数字,允许未来扩展到 4 位以上)</li>
 *   <li>{@code draft} / {@code createdAt} 不允许 {@code null}</li>
 *   <li>{@code filePath} 不允许空白</li>
 * </ul>
 *
 * @author zhourui(V33215020)
 * @since 2026-05-19
 */
public final class IssueLogEntry {

    private static final Pattern ID_PATTERN = Pattern.compile("^I-\\d+$");

    @Getter
    private final String id;
    @Getter
    private final IssueLogDraft draft;
    @Getter
    private final String filePath;
    @Getter
    private final Instant createdAt;

    public IssueLogEntry(String id, IssueLogDraft draft, String filePath, Instant createdAt) {
        this.id = requireValidId(id);
        if (draft == null) {
            throw new IllegalArgumentException("draft required");
        }
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt required");
        }
        this.draft = draft;
        this.filePath = filePath;
        this.createdAt = createdAt;
    }

    private static String requireValidId(String raw) {
        if (raw == null || !ID_PATTERN.matcher(raw).matches()) {
            throw new IllegalArgumentException("id must match I-<digits>, got " + raw);
        }
        return raw;
    }
}
