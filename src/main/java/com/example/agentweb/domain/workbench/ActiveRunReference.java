package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * Workbench 聚合内的活动 Run 引用，不承载 ChatRun 生命周期。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ActiveRunReference {

    private final String runId;
    private final WorkbenchPhase phase;
    private final RunMode runMode;
    private final String reviewConfirmationId;
    private final Long reviewOpinionVersion;
    private final String reviewOpinionHash;
    private final Instant preparedAt;

    public ActiveRunReference(String runId, WorkbenchPhase phase, RunMode runMode,
                              ReviewModifyConfirmation reviewConfirmation,
                              Instant preparedAt) {
        this(runId, phase, runMode,
                reviewConfirmation == null ? null : reviewConfirmation.getConfirmationId(),
                reviewConfirmation == null ? null
                        : Long.valueOf(reviewConfirmation.getOpinionVersion()),
                reviewConfirmation == null ? null : reviewConfirmation.getOpinionHash(),
                preparedAt);
    }

    private ActiveRunReference(
            String runId, WorkbenchPhase phase, RunMode runMode,
            String reviewConfirmationId, Long reviewOpinionVersion,
            String reviewOpinionHash, Instant preparedAt) {
        this.runId = DomainText.require(runId, "workbench run id", 128);
        if (phase == null || runMode == null) {
            throw new IllegalArgumentException("run phase and mode are required");
        }
        this.phase = phase;
        this.runMode = runMode;
        boolean reviewProofPresent = requireConsistentReviewProof(
                reviewConfirmationId, reviewOpinionVersion, reviewOpinionHash);
        PhaseRunPolicy.requireAllowedWithPersistedReviewProof(
                phase, runMode, reviewProofPresent);
        this.reviewConfirmationId = reviewProofPresent
                ? DomainText.require(
                reviewConfirmationId, "review confirmation id", 128) : null;
        this.reviewOpinionVersion = reviewProofPresent
                ? requirePositiveOpinionVersion(reviewOpinionVersion) : null;
        this.reviewOpinionHash = reviewProofPresent
                ? DomainText.requireSha256(
                reviewOpinionHash, "review opinion content hash") : null;
        this.preparedAt = DomainText.requireTime(preparedAt, "run prepared at");
    }

    public static ActiveRunReference restore(
            String runId, WorkbenchPhase phase, RunMode runMode,
            String reviewConfirmationId, Long reviewOpinionVersion,
            String reviewOpinionHash, Instant preparedAt) {
        return new ActiveRunReference(
                runId, phase, runMode, reviewConfirmationId,
                reviewOpinionVersion, reviewOpinionHash, preparedAt);
    }

    public boolean matches(String candidateRunId) {
        return runId.equals(DomainText.require(candidateRunId, "workbench run id", 128));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActiveRunReference)) {
            return false;
        }
        ActiveRunReference that = (ActiveRunReference) other;
        return runId.equals(that.runId)
                && phase == that.phase
                && runMode == that.runMode
                && Objects.equals(reviewConfirmationId, that.reviewConfirmationId)
                && Objects.equals(reviewOpinionVersion, that.reviewOpinionVersion)
                && Objects.equals(reviewOpinionHash, that.reviewOpinionHash)
                && preparedAt.equals(that.preparedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                runId, phase, runMode, reviewConfirmationId,
                reviewOpinionVersion, reviewOpinionHash, preparedAt);
    }

    private static boolean requireConsistentReviewProof(
            String confirmationId, Long opinionVersion, String opinionHash) {
        boolean allAbsent = confirmationId == null
                && opinionVersion == null && opinionHash == null;
        boolean allPresent = confirmationId != null
                && opinionVersion != null && opinionHash != null;
        if (!allAbsent && !allPresent) {
            throw new IllegalArgumentException(
                    "review confirmation snapshot fields must be all present or all absent");
        }
        return allPresent;
    }

    private static Long requirePositiveOpinionVersion(Long version) {
        if (version.longValue() < 1L) {
            throw new IllegalArgumentException(
                    "review opinion version must be positive");
        }
        return version;
    }
}
