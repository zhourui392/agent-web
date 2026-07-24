package com.example.agentweb.domain.harness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对话模式默认能力授权测试。
 *
 * @author alex
 * @since 2026-07-24
 */
class StageCapabilityPolicyTest {

    @Test
    void conversation_should_grant_read_only_workspace_to_analysis_and_design() {
        CapabilityGrant analysis = StageCapabilityPolicy.conversationGrant(HarnessStage.ANALYSIS);
        CapabilityGrant design = StageCapabilityPolicy.conversationGrant(HarnessStage.DESIGN);

        assertTrue(analysis.permits(CapabilityRequest.fileRead("workspace")));
        assertFalse(analysis.permits(CapabilityRequest.fileWrite("workspace")));
        assertTrue(design.permits(CapabilityRequest.fileRead("workspace")));
        assertFalse(design.permits(CapabilityRequest.command("mvn-test")));
    }

    @Test
    void conversation_should_grant_tdd_capabilities_only_to_implementation() {
        CapabilityGrant implementation = StageCapabilityPolicy.conversationGrant(
                HarnessStage.IMPLEMENTATION);

        assertTrue(implementation.permits(CapabilityRequest.fileRead("workspace")));
        assertTrue(implementation.permits(CapabilityRequest.fileWrite("workspace")));
        assertTrue(implementation.permits(CapabilityRequest.command("mvn-test")));
        assertFalse(implementation.permits(CapabilityRequest.command("mvn-verify")));
    }

    @Test
    void conversation_should_grant_release_verification_to_deployment() {
        CapabilityGrant deployment = StageCapabilityPolicy.conversationGrant(
                HarnessStage.DEPLOYMENT);

        assertTrue(deployment.permits(CapabilityRequest.fileRead("workspace")));
        assertFalse(deployment.permits(CapabilityRequest.fileWrite("workspace")));
        assertTrue(deployment.permits(CapabilityRequest.command("mvn-verify")));
    }
}
