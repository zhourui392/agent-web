package com.example.agentweb.config.harness;

import com.example.agentweb.app.harness.HarnessRetirementPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 退役开关默认值与生产装配测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class HarnessRetirementPropertiesTest {

    @Test
    void defaultsShouldKeepCreationMutationAndExportOpen() {
        HarnessRetirementProperties properties =
                new HarnessRetirementProperties();

        assertTrue(properties.isCreationEnabled());
        assertTrue(properties.isMutationEnabled());
        assertTrue(properties.isExportEnabled());

        HarnessRetirementPolicy policy =
                new HarnessRetirementConfiguration()
                        .harnessRetirementPolicy(properties);
        assertDoesNotThrow(policy::requireCreationAvailable);
        assertDoesNotThrow(policy::requireMutationAvailable);
        assertDoesNotThrow(policy::requireExportAvailable);
    }
}
