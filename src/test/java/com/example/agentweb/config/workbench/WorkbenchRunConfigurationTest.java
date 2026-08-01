package com.example.agentweb.config.workbench;

import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import com.example.agentweb.app.workbench.run.WorkbenchRunPreparationSettings;
import com.example.agentweb.app.workbench.run.WorkbenchRunAvailability;
import com.example.agentweb.app.workbench.run.WorkbenchRunUnavailableException;
import com.example.agentweb.config.runtime.CommonRuntimeProperties;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.workbench.RunMode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workbench Run 生产技术限额与准备 Settings 装配测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class WorkbenchRunConfigurationTest {

    @Test
    void availabilityShouldRequireExplicitCommonRuntimeEnablement() {
        CommonRuntimeProperties runtime = new CommonRuntimeProperties();
        WorkbenchReleasePolicy release = new WorkbenchReleasePolicy(
                true, false, false, false, false, false, false);
        WorkbenchRunAvailability availability =
                new WorkbenchRunConfiguration()
                        .workbenchRunAvailability(runtime, release);

        assertThrows(WorkbenchRunUnavailableException.class,
                () -> availability.requireAvailable(
                        RunMode.DISCUSS_READ_ONLY));

        runtime.setWorkbenchEnabled(true);
        availability.requireAvailable(RunMode.DISCUSS_READ_ONLY);
        assertThrows(WorkbenchRunUnavailableException.class,
                () -> availability.requireAvailable(
                        RunMode.MODIFY_WORKSPACE));

        WorkbenchReleasePolicy writeEnabled = new WorkbenchReleasePolicy(
                true, false, true, false, false, false, false);
        WorkbenchRunAvailability writeAvailability =
                new WorkbenchRunConfiguration()
                        .workbenchRunAvailability(runtime, writeEnabled);
        writeAvailability.requireAvailable(RunMode.MODIFY_WORKSPACE);
    }

    @Test
    void defaultsShouldCreateFailClosedRunPreparationSettings() {
        WorkbenchCapabilityProperties capability =
                new WorkbenchCapabilityProperties();
        WorkbenchRunProperties run = new WorkbenchRunProperties();
        CommonRuntimeProperties runtime = new CommonRuntimeProperties();

        WorkbenchRunPreparationSettings settings =
                new WorkbenchRunConfiguration()
                        .workbenchRunPreparationSettings(
                                capability, run, runtime);

        assertEquals("workbench-policy@1",
                settings.getCapabilityPolicyVersion());
        assertEquals("m0-2026-07-22",
                settings.getRuntimeCompatibility());
        assertEquals(1800L,
                settings.getRuntimeLimits().getTimeout().getSeconds());
        assertEquals(8L * 1024L * 1024L,
                settings.getRuntimeLimits().getMaxOutputBytes());
        assertEquals(Collections.singleton(SkillTrustSource.PLATFORM),
                settings.getAllowedSkillTrustSources());
        assertTrue(settings.getRuntimeLimits()
                .getEnvironmentAllowlist().isEmpty());
    }

    @Test
    void invalidRunLimitsShouldFailBeforeSettingsBeanCreation() {
        WorkbenchRunProperties run = new WorkbenchRunProperties();
        run.setTimeoutSeconds(0L);

        assertThrows(IllegalStateException.class,
                () -> new WorkbenchRunConfiguration()
                        .workbenchRunPreparationSettings(
                                new WorkbenchCapabilityProperties(), run,
                                new CommonRuntimeProperties()));
    }
}
