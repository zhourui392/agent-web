package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.config.AgentRunProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public class RunRecallPolicyFactoryTest {

    @Test
    public void forRun_shouldApplyRuntimeSwitchesAndTopK() {
        AgentRunProperties properties = new AgentRunProperties();
        properties.setWorkspaceContextEnabled(false);
        properties.setWorkspaceKnowledgeEnabled(false);
        properties.setRecallTopK(3);
        RunRecallPolicyFactory factory = new RunRecallPolicyFactory(properties);

        RunRecallPolicy policy = factory.forRun(RunForm.DIAGNOSE, SourceType.DIAGNOSE);

        assertFalse(policy.isWorkspaceContextEnabled());
        assertFalse(policy.isWorkspaceKnowledgeEnabled());
        assertTrue(policy.isHistoricalRagEnabled());
        assertEquals(SourceType.DIAGNOSE, policy.getHistoricalSourceFilter());
        assertEquals(3, policy.getTopK());
    }

    @Test
    public void forRun_shouldKeepWorkspaceKnowledgeDisabledForCustomRunEvenWhenSwitchEnabled() {
        AgentRunProperties properties = new AgentRunProperties();
        properties.setWorkspaceContextEnabled(true);
        properties.setWorkspaceKnowledgeEnabled(true);
        RunRecallPolicyFactory factory = new RunRecallPolicyFactory(properties);

        RunRecallPolicy policy = factory.forRun(RunForm.CUSTOM, SourceType.GENERAL);

        assertTrue(policy.isWorkspaceContextEnabled());
        assertFalse(policy.isWorkspaceKnowledgeEnabled());
        assertFalse(policy.isHistoricalRagEnabled());
        assertEquals(SourceType.GENERAL, policy.getHistoricalSourceFilter());
    }

    @Test
    public void agentRunContext_shouldDisableRecallByDefaultWhenPolicyIsNotProvided() {
        AgentRunContext context = AgentRunContext.builder()
                .runForm(RunForm.DIAGNOSE)
                .sourceDomain(SourceType.DIAGNOSE)
                .build();

        assertFalse(context.getRecallPolicy().isWorkspaceContextEnabled());
        assertFalse(context.getRecallPolicy().isWorkspaceKnowledgeEnabled());
        assertFalse(context.getRecallPolicy().isHistoricalRagEnabled());
    }
}
