package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 人工 Review 意见的不可变版本引用；持久化规范正文，运行授权绑定服务端计算的 Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewOpinion {

    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final long version;
    private final String content;
    private final String contentHash;
    private final OwnerReference reviewedBy;
    private final Instant reviewedAt;

    private ReviewOpinion(WorkbenchId workbenchId, long version,
                          String content, String contentHash,
                          OwnerReference reviewedBy, Instant reviewedAt) {
        if (workbenchId == null || reviewedBy == null) {
            throw new IllegalArgumentException(
                    "review opinion workbench and actor are required");
        }
        if (version < 1L) {
            throw new IllegalArgumentException("review opinion version must be positive");
        }
        this.workbenchId = workbenchId;
        this.phase = WorkbenchPhase.REVIEW_REFACTOR;
        this.version = version;
        this.content = normalizeLegacyContent(content);
        this.contentHash = DomainText.requireSha256(
                contentHash, "review opinion content hash");
        if (this.content != null
                && !this.contentHash.equals(hashContent(this.content))) {
            throw new IllegalArgumentException(
                    "review opinion content hash must match its content");
        }
        this.reviewedBy = reviewedBy;
        this.reviewedAt = DomainText.requireTime(reviewedAt, "review opinion time");
    }

    public static ReviewOpinion record(WorkbenchId workbenchId, long version,
                                       String contentHash, OwnerReference reviewedBy,
                                       Instant reviewedAt) {
        return new ReviewOpinion(
                workbenchId, version, null, contentHash,
                reviewedBy, reviewedAt);
    }

    public static ReviewOpinion restore(
            WorkbenchId workbenchId, long version,
            String content, String contentHash,
            OwnerReference reviewedBy, Instant reviewedAt) {
        return new ReviewOpinion(
                workbenchId, version, content, contentHash,
                reviewedBy, reviewedAt);
    }

    public static ReviewOpinion start(
            WorkbenchId workbenchId, long expectedVersion,
            String content, OwnerReference reviewedBy,
            Instant reviewedAt) {
        if (expectedVersion != 0L) {
            throw versionConflict();
        }
        String normalized = requireContent(content);
        return new ReviewOpinion(
                workbenchId, 1L, normalized, hashContent(normalized),
                reviewedBy, reviewedAt);
    }

    public ReviewOpinion revise(
            long expectedVersion, String content,
            OwnerReference reviewedBy, Instant reviewedAt) {
        requireExactVersion(expectedVersion);
        String normalized = requireContent(content);
        return new ReviewOpinion(
                workbenchId, version + 1L, normalized,
                hashContent(normalized),
                reviewedBy, reviewedAt);
    }

    public ReviewModifyConfirmation confirmModify(
            String confirmationId, long opinionVersion,
            String opinionHash, OwnerReference confirmedBy,
            Instant confirmedAt) {
        requireExact(opinionVersion, opinionHash);
        if (!reviewedBy.sameIdentityAs(confirmedBy)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "only the opinion reviewer can confirm its modification");
        }
        return ReviewModifyConfirmation.confirm(
                confirmationId, this, confirmedBy, confirmedAt);
    }

    public void requireExact(long expectedVersion, String expectedHash) {
        String normalizedHash = DomainText.requireSha256(
                expectedHash, "review opinion expected hash");
        if (version != expectedVersion || !contentHash.equals(normalizedHash)) {
            throw versionConflict();
        }
    }

    public void requireExactContent(String candidateContent) {
        String normalized = requireContent(candidateContent);
        if (!contentHash.equals(hashContent(normalized))) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                    "review modify message must match the confirmed opinion");
        }
    }

    private void requireExactVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw versionConflict();
        }
    }

    private static WorkbenchDomainException versionConflict() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.VERSION_CONFLICT,
                "review opinion version or hash changed");
    }

    private static String requireContent(String value) {
        return DomainText.require(value, "review opinion content", 16000);
    }

    private static String normalizeLegacyContent(String value) {
        return value == null ? null : requireContent(value);
    }

    private static String hashContent(String value) {
        return CanonicalHashing.sha256(value);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewOpinion)) {
            return false;
        }
        ReviewOpinion that = (ReviewOpinion) other;
        return version == that.version
                && workbenchId.equals(that.workbenchId)
                && contentHash.equals(that.contentHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workbenchId, version, contentHash);
    }
}
