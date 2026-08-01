package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Locale;
import java.util.Objects;

/**
 * 工作区级异常证据：采集失败、输出截断、路径越界、二次核验不一致等。
 *
 * <p>不进入 diffHash，但会阻止 {@code WorkspaceSnapshot.clean=true}。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class WorkspaceAnomalyEvidence {

    public enum Kind {
        CAPTURE_FAILED,
        OUTPUT_TRUNCATED,
        PATH_OUT_OF_BOUNDS,
        SECONDARY_VERIFY_MISMATCH,
        CHANGED_FILES_LIMIT_EXCEEDED,
        OTHER
    }

    private final Kind kind;
    private final String repositoryKey;
    private final String detail;

    public WorkspaceAnomalyEvidence(Kind kind, String repositoryKey, String detail) {
        if (kind == null) {
            throw new IllegalArgumentException("anomaly kind must not be null");
        }
        this.kind = kind;
        this.repositoryKey = repositoryKey == null || repositoryKey.trim().isEmpty()
                ? null
                : RepositorySelection.normalizeRepositoryKey(repositoryKey);
        this.detail = DomainText.require(detail, "anomaly detail", 2048);
    }

    public static WorkspaceAnomalyEvidence of(Kind kind, String repositoryKey, String detail) {
        return new WorkspaceAnomalyEvidence(kind, repositoryKey, detail);
    }

    public static WorkspaceAnomalyEvidence workspaceLevel(Kind kind, String detail) {
        return new WorkspaceAnomalyEvidence(kind, null, detail);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkspaceAnomalyEvidence)) {
            return false;
        }
        WorkspaceAnomalyEvidence that = (WorkspaceAnomalyEvidence) other;
        return kind == that.kind
                && Objects.equals(repositoryKey, that.repositoryKey)
                && detail.equals(that.detail);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, repositoryKey, detail);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT)
                + (repositoryKey == null ? "" : "@" + repositoryKey)
                + ": " + detail;
    }
}
