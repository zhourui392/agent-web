package com.example.agentweb.domain.workspace;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * Git status 中的单个变更文件及敏感路径判定。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
public final class ChangedFileEvidence {

    private final String path;
    private final String status;
    private final String stateFingerprint;
    private final boolean sensitive;

    public ChangedFileEvidence(String path, boolean sensitive) {
        this(path, "UNKNOWN", CanonicalHashing.sha256(
                DomainText.require(path, "changed file path", 4096)
                        .getBytes(StandardCharsets.UTF_8)), sensitive);
    }

    public ChangedFileEvidence(String path, String status, String stateFingerprint,
                               boolean sensitive) {
        this.path = DomainText.require(path, "changed file path", 4096);
        this.status = DomainText.require(status, "changed file status", 64);
        this.stateFingerprint = DomainText.requireSha256(
                stateFingerprint, "changed file state fingerprint");
        if (WorkspaceSensitivePathPolicy.isSensitive(this.path) && !sensitive) {
            throw new IllegalArgumentException("sensitive changed file path must be classified");
        }
        this.sensitive = sensitive;
    }

    public static ChangedFileEvidence observed(String path, String status,
                                               String stateFingerprint) {
        return new ChangedFileEvidence(path, status, stateFingerprint, isSensitivePath(path));
    }

    public boolean sameStateAs(ChangedFileEvidence other) {
        return other != null && path.equals(other.path) && status.equals(other.status)
                && stateFingerprint.equals(other.stateFingerprint);
    }

    public ChangedFileEvidence removedFromBaseline() {
        return new ChangedFileEvidence(path, "REMOVED_FROM_BASELINE",
                CanonicalHashing.sha256(("removed:" + stateFingerprint)
                        .getBytes(StandardCharsets.UTF_8)), sensitive);
    }

    private static boolean isSensitivePath(String value) {
        return WorkspaceSensitivePathPolicy.isSensitive(value);
    }
}
