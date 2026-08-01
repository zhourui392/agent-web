package com.example.agentweb.config.workbench;

import com.example.agentweb.app.runtime.port.RuntimeLimits;
import com.example.agentweb.app.workbench.WorkbenchReleasePolicy;
import com.example.agentweb.app.workbench.WorkbenchReleaseUnavailableException;
import com.example.agentweb.app.workbench.run.WorkbenchRunPreparationSettings;
import com.example.agentweb.app.workbench.run.WorkbenchRunAvailability;
import com.example.agentweb.app.workbench.run.WorkbenchRunUnavailableException;
import com.example.agentweb.config.runtime.CommonRuntimeProperties;
import com.example.agentweb.domain.workbench.RunMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Collections;

/**
 * Workbench Run 准备策略的生产装配。
 *
 * @author alex
 * @since 2026-08-01
 */
@Configuration
public class WorkbenchRunConfiguration {

    @Bean
    public WorkbenchRunAvailability workbenchRunAvailability(
            CommonRuntimeProperties runtimeProperties,
            WorkbenchReleasePolicy releasePolicy) {
        return new WorkbenchRunAvailability() {
            @Override
            public void requireAvailable(RunMode runMode) {
                if (!runtimeProperties.isWorkbenchEnabled()) {
                    throw new WorkbenchRunUnavailableException();
                }
                try {
                    releasePolicy.requireRunAvailable(runMode);
                } catch (WorkbenchReleaseUnavailableException failure) {
                    throw new WorkbenchRunUnavailableException();
                }
            }
        };
    }

    @Bean
    public WorkbenchRunPreparationSettings workbenchRunPreparationSettings(
            WorkbenchCapabilityProperties capabilityProperties,
            WorkbenchRunProperties runProperties,
            CommonRuntimeProperties runtimeProperties) {
        runProperties.validate();
        return new WorkbenchRunPreparationSettings(
                capabilityProperties.getPolicyVersion(),
                runtimeProperties.getCompatibilityMatrixVersion(),
                runProperties.getAllowedSkillTrustSources(),
                new RuntimeLimits(
                        Duration.ofSeconds(
                                runProperties.getTimeoutSeconds()),
                        runProperties.getMaxOutputBytes(),
                        Collections.<String>emptySet()));
    }
}
