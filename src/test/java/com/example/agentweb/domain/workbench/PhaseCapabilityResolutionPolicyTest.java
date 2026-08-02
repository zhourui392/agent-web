package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumSet;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Effective Profile 预览策略的 Phase、Runtime 与信任边界测试。
 *
 * @author alex
 * @since 2026-08-01
 */
class PhaseCapabilityResolutionPolicyTest {

    @Test
    void profilePreviewShouldUseModifyOnlyForImplementationPhase() {
        for (WorkbenchPhase phase : WorkbenchPhase.values()) {
            PhaseCapabilityResolutionPolicy policy =
                    PhaseCapabilityResolutionPolicy.forProfilePreview(
                            "workbench-policy@1", phase, AgentType.CODEX,
                            developmentContext(
                                    RepositoryDevelopmentMarker.POM_XML));

            assertEquals(phase == WorkbenchPhase.IMPLEMENT_TEST
                            ? RunMode.MODIFY_WORKSPACE
                            : RunMode.DISCUSS_READ_ONLY,
                    policy.getRunMode());
            assertEquals(phase == WorkbenchPhase.IMPLEMENT_TEST
                            ? CapabilityAccess.WRITE : CapabilityAccess.READ,
                    policy.getMaximumMcpAccess());
            assertEquals("CODEX", policy.getRuntime());
            assertEquals("CODEX_WORKBENCH@1",
                    policy.getRuntimeCompatibility());
            assertTrue(policy.allowsSkillTrustSource(
                    SkillTrustSource.PLATFORM));
            assertFalse(policy.allowsSkillTrustSource(
                    SkillTrustSource.WORKSPACE));
            assertEquals(new TreeSet<String>(java.util.Arrays.asList(
                            "java", "maven")),
                    policy.getWorkspaceCapabilityTags());
        }
    }

    @Test
    void profilePreviewShouldKeepPlatformDefaultsWithoutTechnologyMarker() {
        PhaseCapabilityResolutionPolicy policy =
                PhaseCapabilityResolutionPolicy.forProfilePreview(
                        "workbench-policy@1",
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        AgentType.CODEX,
                        developmentContext(
                                RepositoryDevelopmentMarker.README_MARKDOWN));

        assertTrue(policy.getWorkspaceCapabilityTags().isEmpty());
    }

    @Test
    void profilePreviewShouldRejectNativeRuntime() {
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityResolutionPolicy.forProfilePreview(
                        "workbench-policy@1",
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        AgentType.NATIVE,
                        developmentContext(
                                RepositoryDevelopmentMarker.POM_XML)));
    }

    private static WorkspaceDevelopmentContext developmentContext(
            RepositoryDevelopmentMarker marker) {
        return WorkspaceDevelopmentContext.create(
                repeat('a'), "agent-web",
                Collections.singletonList(
                        new RepositoryDevelopmentContextClassifier().classify(
                                "agent-web", EnumSet.of(marker))));
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        java.util.Arrays.fill(values, value);
        return new String(values);
    }
}
