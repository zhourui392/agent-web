package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 显式 WRITE Grant 与 Runtime sandbox 的最小权限求交测试。
 *
 * @author alex
 * @since 2026-07-23
 */
class SkillSelectionRuntimeEnforcementTest {

    @Test
    void implementationShouldUseWorkspaceWriteOnlyWhenWriteWasAuthorized() {
        RuntimeEnforcementProfile maximum = profile("workspace-write");
        SkillSelection authorized = selection(true);
        SkillSelection denied = selection(false);

        assertEquals("workspace-write", authorized.enforceRuntimeProfile(
                HarnessStage.IMPLEMENTATION, maximum).getSandboxMode());
        assertEquals("read-only", denied.enforceRuntimeProfile(
                HarnessStage.IMPLEMENTATION, maximum).getSandboxMode());
        assertEquals("read-only", authorized.enforceRuntimeProfile(
                HarnessStage.ANALYSIS, maximum).getSandboxMode());
    }

    private SkillSelection selection(boolean authorized) {
        CapabilityDecision decision = new CapabilityDecision("java-tdd",
                CapabilityRequest.fileWrite("workspace"), authorized,
                authorized ? "granted" : "denied");
        return new SkillSelection(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(decision));
    }

    private RuntimeEnforcementProfile profile(String sandbox) {
        return new RuntimeEnforcementProfile("profile", "adapter", "codex-cli 0.145.0",
                "matrix", sandbox, true, true, true, true, true, true);
    }
}
