package com.example.agentweb.app.workbench;

import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workbench 发布策略的总开关与读写 Run 求交测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchReleasePolicyTest {

    @Test
    void disabledMasterShouldCloseCreateAndEveryRunMode() {
        WorkbenchReleasePolicy policy = new WorkbenchReleasePolicy(
                false, true, true);

        assertThrows(WorkbenchReleaseUnavailableException.class,
                policy::requireCreationAvailable);
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> policy.requireRunAvailable(
                        RunMode.DISCUSS_READ_ONLY));
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> policy.requireRunAvailable(
                        RunMode.MODIFY_WORKSPACE));
    }

    @Test
    void createAndWriteRunShouldRemainIndependent() {
        WorkbenchReleasePolicy readOnly = new WorkbenchReleasePolicy(
                true, false, false);

        assertThrows(WorkbenchReleaseUnavailableException.class,
                readOnly::requireCreationAvailable);
        assertDoesNotThrow(() -> readOnly.requireRunAvailable(
                RunMode.DISCUSS_READ_ONLY));
        assertThrows(WorkbenchReleaseUnavailableException.class,
                () -> readOnly.requireRunAvailable(
                        RunMode.MODIFY_WORKSPACE));

        WorkbenchReleasePolicy enabled = new WorkbenchReleasePolicy(
                true, true, true);
        assertDoesNotThrow(enabled::requireCreationAvailable);
        assertDoesNotThrow(() -> enabled.requireRunAvailable(
                RunMode.MODIFY_WORKSPACE));
    }

    @Test
    void missingRunModeShouldFailClosed() {
        WorkbenchReleasePolicy policy = new WorkbenchReleasePolicy(
                true, true, true);

        assertThrows(IllegalArgumentException.class,
                () -> policy.requireRunAvailable(null));
    }
}
