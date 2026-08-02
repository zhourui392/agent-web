package com.example.agentweb.domain.workbench;

/**
 * 四阶段读写意图策略，收敛阶段与权限组合规则。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class PhaseRunPolicy {

    private PhaseRunPolicy() {
    }

    public static void requireAllowed(WorkbenchPhase phase, RunMode mode,
                                      ReviewModifyConfirmation reviewConfirmation) {
        requireAllowed(phase, mode, reviewConfirmation != null);
    }

    static void requireAllowedWithPersistedReviewProof(
            WorkbenchPhase phase, RunMode mode, boolean reviewProofPresent) {
        requireAllowed(phase, mode, reviewProofPresent);
    }

    private static void requireAllowed(WorkbenchPhase phase, RunMode mode,
                                       boolean reviewProofPresent) {
        if (phase == null || mode == null) {
            throw new IllegalArgumentException("workbench phase and run mode are required");
        }
        if (reviewProofPresent
                && (phase != WorkbenchPhase.REVIEW_REFACTOR
                || mode != RunMode.MODIFY_WORKSPACE)) {
            throw forbidden(phase,
                    "review confirmation is only valid for a review modify run");
        }
    }

    private static WorkbenchDomainException forbidden(WorkbenchPhase phase, String reason) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.RUN_MODE_FORBIDDEN,
                "run mode is forbidden for phase " + phase + ": " + reason);
    }
}
