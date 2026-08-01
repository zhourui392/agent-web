package com.example.agentweb.config.workbench;

import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import com.example.agentweb.domain.workbench.HighImpactOperationType;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench 发布属性到不可变策略的生产装配测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchReleaseConfigurationTest {

    @Test
    void configurationShouldMapLocalDeployWithoutOpeningOtherExecutors() {
        WorkbenchReleaseProperties properties =
                new WorkbenchReleaseProperties();
        properties.setEnabled(true);
        properties.setCreateEnabled(true);
        properties.setWriteRunEnabled(true);
        properties.getHighImpact().setLocalDeployEnabled(true);

        WorkbenchReleasePolicy policy =
                new WorkbenchReleaseConfiguration()
                        .workbenchReleasePolicy(properties);

        assertDoesNotThrow(policy::requireCreationAvailable);
        assertDoesNotThrow(() -> policy.requireRunAvailable(
                RunMode.MODIFY_WORKSPACE));
        assertTrue(policy.isHighImpactExecutionAvailable(
                HighImpactOperationType.LOCAL_DEPLOY));
        assertFalse(policy.isHighImpactExecutionAvailable(
                HighImpactOperationType.GIT_COMMIT));
        assertFalse(policy.isHighImpactExecutionAvailable(
                HighImpactOperationType.GIT_PUSH));
        assertFalse(policy.isHighImpactExecutionAvailable(
                HighImpactOperationType.PRODUCTION_WRITE));
    }
}
