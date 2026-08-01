package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.Objects;

/**
 * Review 阶段执行已确认重构的显式人工意见版本引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewModifyConfirmation {

    private final String confirmationId;
    private final ReviewOpinion opinion;
    private final WorkbenchPhase phase;
    private final OwnerReference confirmedBy;
    private final java.time.Instant confirmedAt;

    private ReviewModifyConfirmation(String confirmationId, ReviewOpinion opinion,
                                     OwnerReference confirmedBy,
                                     java.time.Instant confirmedAt) {
        this.confirmationId = DomainText.require(
                confirmationId, "review confirmation id", 128);
        if (opinion == null || confirmedBy == null) {
            throw new IllegalArgumentException(
                    "review opinion and confirmation actor are required");
        }
        this.opinion = opinion;
        this.phase = WorkbenchPhase.REVIEW_REFACTOR;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = DomainText.requireTime(
                confirmedAt, "review confirmation time");
        if (confirmedAt.isBefore(opinion.getReviewedAt())) {
            throw new IllegalArgumentException(
                    "review confirmation cannot precede the opinion");
        }
    }

    public static ReviewModifyConfirmation confirm(
            String confirmationId, ReviewOpinion opinion,
            OwnerReference confirmedBy, java.time.Instant confirmedAt) {
        return new ReviewModifyConfirmation(
                confirmationId, opinion, confirmedBy, confirmedAt);
    }

    public boolean isValidFor(WorkbenchId workbenchId, OwnerReference actor,
                              java.time.Instant runPreparedAt) {
        return workbenchId != null
                && workbenchId.equals(opinion.getWorkbenchId())
                && phase == opinion.getPhase()
                && confirmedBy.sameIdentityAs(actor)
                && opinion.getReviewedBy().sameIdentityAs(actor)
                && !confirmedAt.isAfter(runPreparedAt);
    }

    public long getOpinionVersion() {
        return opinion.getVersion();
    }

    public String getOpinionHash() {
        return opinion.getContentHash();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ReviewModifyConfirmation)) {
            return false;
        }
        ReviewModifyConfirmation that = (ReviewModifyConfirmation) other;
        return confirmationId.equals(that.confirmationId)
                && opinion.equals(that.opinion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(confirmationId, opinion);
    }
}
