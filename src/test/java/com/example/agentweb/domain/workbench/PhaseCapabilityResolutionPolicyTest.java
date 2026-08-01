package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.capability.CapabilityAccess;
import com.example.agentweb.domain.capability.SkillTrustSource;
import com.example.agentweb.domain.shared.AgentType;
import org.junit.jupiter.api.Test;

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
                            "workbench-policy@1", phase, AgentType.CODEX);

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
        }
    }

    @Test
    void profilePreviewShouldRejectNativeRuntime() {
        assertThrows(WorkbenchDomainException.class,
                () -> PhaseCapabilityResolutionPolicy.forProfilePreview(
                        "workbench-policy@1",
                        WorkbenchPhase.REQUIREMENT_ANALYSIS,
                        AgentType.NATIVE));
    }
}
