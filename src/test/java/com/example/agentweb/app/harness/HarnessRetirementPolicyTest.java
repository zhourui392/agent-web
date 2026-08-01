package com.example.agentweb.app.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Harness 退役窗口分级门禁测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class HarnessRetirementPolicyTest {

    @Test
    void stoppedCreationShouldKeepExistingMutationAndExportAvailable() {
        HarnessRetirementPolicy policy =
                new HarnessRetirementPolicy(false, true, true);

        assertCode("HARNESS_CREATION_DISABLED",
                policy::requireCreationAvailable);
        assertDoesNotThrow(policy::requireMutationAvailable);
        assertDoesNotThrow(policy::requireExportAvailable);
    }

    @Test
    void readOnlyWindowShouldRejectEveryWriteIncludingCreation() {
        HarnessRetirementPolicy policy =
                new HarnessRetirementPolicy(true, false, true);

        assertCode("HARNESS_MUTATION_DISABLED",
                policy::requireMutationAvailable);
        assertCode("HARNESS_MUTATION_DISABLED",
                policy::requireCreationAvailable);
        assertDoesNotThrow(policy::requireExportAvailable);
    }

    @Test
    void stoppedExportShouldNotCloseCreationOrMutation() {
        HarnessRetirementPolicy policy =
                new HarnessRetirementPolicy(true, true, false);

        assertDoesNotThrow(policy::requireCreationAvailable);
        assertDoesNotThrow(policy::requireMutationAvailable);
        assertCode("HARNESS_EXPORT_DISABLED",
                policy::requireExportAvailable);
    }

    private static void assertCode(
            String expectedCode, Runnable action) {
        HarnessRetirementUnavailableException failure = assertThrows(
                HarnessRetirementUnavailableException.class, action::run);
        assertEquals(expectedCode, failure.getCode());
    }
}
