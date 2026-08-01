package com.example.agentweb.app.workbench.review;

import com.example.agentweb.domain.workbench.ReviewModifyConfirmation;
import com.example.agentweb.domain.workbench.WorkbenchPhase;
import lombok.Getter;

/**
 * 不暴露 actor 或 Opinion 内部对象的 Review Confirmation 安全投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewConfirmationView {

    private final String confirmationId;
    private final WorkbenchPhase phase;
    private final long opinionVersion;
    private final String opinionHash;
    private final long confirmedAt;
    private final boolean readOnly;

    private ReviewConfirmationView(
            String confirmationId, WorkbenchPhase phase,
            long opinionVersion, String opinionHash,
            long confirmedAt, boolean readOnly) {
        this.confirmationId = confirmationId;
        this.phase = phase;
        this.opinionVersion = opinionVersion;
        this.opinionHash = opinionHash;
        this.confirmedAt = confirmedAt;
        this.readOnly = readOnly;
    }

    public static ReviewConfirmationView from(
            ReviewModifyConfirmation confirmation, boolean readOnly) {
        return new ReviewConfirmationView(
                confirmation.getConfirmationId(), confirmation.getPhase(),
                confirmation.getOpinionVersion(),
                confirmation.getOpinionHash(),
                confirmation.getConfirmedAt().toEpochMilli(), readOnly);
    }
}
