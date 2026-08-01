package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 发布策略的总开关、读写 Run 和高影响操作求交测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchReleasePolicyTest {

    @Test
    void disabledMasterShouldCloseCreateRunAndEveryHighImpactExecutor() {
        WorkbenchReleasePolicy policy = policy(
                false, true, true, true, true, true, true);

        assertThrows(WorkbenchReleaseUnavailableException.class,
                policy::requireCreationAvailable);
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> policy.requireRunAvailable(
                        RunMode.DISCUSS_READ_ONLY));
        for (HighImpactOperationType type
                : HighImpactOperationType.values()) {
            assertFalse(policy.isHighImpactExecutionAvailable(type));
            assertThrows(WorkbenchReleaseUnavailableException.class,
                    () -> policy.requireHighImpactExecutionAvailable(type));
        }
    }

    @Test
    void createAndWriteRunShouldBeIndependentWhileReadOnlyRunRemainsAvailable() {
        WorkbenchReleasePolicy policy = policy(
                true, false, false, false, false, false, false);

        assertThrows(WorkbenchReleaseUnavailableException.class,
                policy::requireCreationAvailable);
        assertDoesNotThrow(() -> policy.requireRunAvailable(
                RunMode.DISCUSS_READ_ONLY));
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> policy.requireRunAvailable(
                        RunMode.MODIFY_WORKSPACE));

        WorkbenchReleasePolicy createOnly = policy(
                true, true, false, false, false, false, false);
        assertDoesNotThrow(createOnly::requireCreationAvailable);
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> createOnly.requireRunAvailable(
                        RunMode.MODIFY_WORKSPACE));
    }

    @Test
    void eachHighImpactFlagShouldOpenOnlyItsExactOperationType() {
        assertExactHighImpactFlag(
                HighImpactOperationType.GIT_COMMIT,
                policy(true, false, false, true, false, false, false));
        assertExactHighImpactFlag(
                HighImpactOperationType.GIT_PUSH,
                policy(true, false, false, false, true, false, false));
        assertExactHighImpactFlag(
                HighImpactOperationType.LOCAL_DEPLOY,
                policy(true, false, false, false, false, true, false));
        assertExactHighImpactFlag(
                HighImpactOperationType.PRODUCTION_WRITE,
                policy(true, false, false, false, false, false, true));
    }

    @Test
    void invalidOperationTypeShouldFailClosed() {
        WorkbenchReleasePolicy policy = policy(
                true, false, false, true, true, true, true);

        assertThrows(IllegalArgumentException.class,
                () -> policy.isHighImpactExecutionAvailable(null));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireRunAvailable(null));
    }

    private void assertExactHighImpactFlag(
            HighImpactOperationType enabledType,
            WorkbenchReleasePolicy policy) {
        for (HighImpactOperationType candidate
                : HighImpactOperationType.values()) {
            if (candidate == enabledType) {
                assertTrue(policy.isHighImpactExecutionAvailable(candidate));
                assertDoesNotThrow(() -> policy
                        .requireHighImpactExecutionAvailable(candidate));
            } else {
                assertFalse(policy.isHighImpactExecutionAvailable(candidate));
                assertThrows(WorkbenchReleaseUnavailableException.class,
                        () -> policy.requireHighImpactExecutionAvailable(
                                candidate));
            }
        }
    }

    private WorkbenchReleasePolicy policy(
            boolean enabled, boolean createEnabled,
            boolean writeRunEnabled, boolean commitEnabled,
            boolean pushEnabled, boolean localDeployEnabled,
            boolean productionWriteEnabled) {
        return new WorkbenchReleasePolicy(
                enabled, createEnabled, writeRunEnabled,
                commitEnabled, pushEnabled, localDeployEnabled,
                productionWriteEnabled);
    }
}
