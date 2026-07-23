package com.example.agentweb.app.agentrun;

import com.example.agentweb.domain.refinery.SourceType;
import com.example.agentweb.config.AgentRunProperties;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Builds AgentRun recall policy from run form, source domain and runtime configuration.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Component
public class RunRecallPolicyFactory {

    private final AgentRunProperties properties;

    public RunRecallPolicyFactory(AgentRunProperties properties) {
        this.properties = properties;
    }

    /** workspace 知识预召回默认放行的 run 形态。 */
    private static final Set<RunForm> WORKSPACE_KNOWLEDGE_FORMS = EnumSet.of(
            RunForm.DIAGNOSE, RunForm.WORKFLOW_STEP, RunForm.SCHEDULED);

    public RunRecallPolicy forRun(RunForm runForm, SourceType sourceDomain) {
        boolean defaultWorkspaceKnowledge = WORKSPACE_KNOWLEDGE_FORMS.contains(runForm);
        return RunRecallPolicy.builder()
                .workspaceContextEnabled(properties.isWorkspaceContextEnabled())
                .workspaceKnowledgeEnabled(properties.isWorkspaceKnowledgeEnabled()
                        && defaultWorkspaceKnowledge)
                .historicalRagEnabled(sourceDomain == SourceType.DIAGNOSE)
                .historicalSourceFilter(sourceDomain)
                .topK(properties.getRecallTopK())
                .persistObservation(true)
                .build();
    }
}
