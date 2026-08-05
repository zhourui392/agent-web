package com.example.agentweb.config.workbench;

import com.example.agentweb.domain.capability.CapabilityArtifactRegistry;
import com.example.agentweb.domain.workbench.stage.WorkbenchStageCapabilityResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Workbench Stage Capability 领域服务装配测试。
 *
 * @author alex
 * @since 2026-08-05
 */
class WorkbenchCapabilityConfigurationTest {

    @Test
    void should_ExposeStageCapabilityResolverUsingArtifactRegistry() {
        // Given
        WorkbenchCapabilityConfiguration configuration =
                new WorkbenchCapabilityConfiguration();
        CapabilityArtifactRegistry artifactRegistry =
                mock(CapabilityArtifactRegistry.class);

        // When
        WorkbenchStageCapabilityResolver resolver =
                configuration.workbenchStageCapabilityResolver(artifactRegistry);

        // Then
        assertNotNull(resolver);
    }
}
