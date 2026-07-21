package com.example.agentweb.app.agentrun;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Mutable prompt assembly state scoped to one run.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public class PromptAssembly {

    @Getter
    private AgentRunContext context;
    private final List<PromptPart> parts = new ArrayList<PromptPart>();
    @Getter
    private Boolean historicalRagUsed;
    @Getter
    private String historicalRagChunkIdsJson;
    @Getter
    private boolean userInputOwnedByHistoricalRag;
    @Getter
    private boolean envGuardrailApplied;
    @Getter
    private boolean workspaceGuardrailApplied;
    @Getter
    private final List<String> workspaceKnowledgeHits = new ArrayList<String>();
    @Getter
    private final List<String> workspaceContextDocs = new ArrayList<String>();
    private final List<RecallContribution> recallContributions =
            new ArrayList<RecallContribution>();

    public PromptAssembly(AgentRunContext context) {
        this.context = context;
    }

    public void setContext(AgentRunContext context) {
        this.context = context;
    }

    public void addPart(PromptPartType type, String title, String content) {
        if (content == null || content.trim().isEmpty()) {
            return;
        }
        parts.add(new PromptPart(type, title, content.trim(), null));
    }

    public List<PromptPart> parts() {
        return Collections.unmodifiableList(parts);
    }

    public void markHistoricalRag(Boolean used, String chunkIdsJson, boolean ownsUserInput) {
        this.historicalRagUsed = used;
        this.historicalRagChunkIdsJson = chunkIdsJson;
        this.userInputOwnedByHistoricalRag = ownsUserInput;
    }

    public void markEnvGuardrailApplied() {
        this.envGuardrailApplied = true;
    }

    public void markWorkspaceGuardrailApplied() {
        this.workspaceGuardrailApplied = true;
    }

    public void addWorkspaceContextDoc(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String trimmed = path.trim();
        if (!workspaceContextDocs.contains(trimmed)) {
            workspaceContextDocs.add(trimmed);
        }
    }

    public void addWorkspaceKnowledgeHit(String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        String trimmed = path.trim();
        if (!workspaceKnowledgeHits.contains(trimmed)) {
            workspaceKnowledgeHits.add(trimmed);
        }
    }

    public void addRecallContribution(RecallContribution contribution) {
        if (contribution != null) {
            recallContributions.add(contribution);
        }
    }

    public List<RecallContribution> recallContributions() {
        return Collections.unmodifiableList(recallContributions);
    }

    public String guardrailSource() {
        if (envGuardrailApplied && workspaceGuardrailApplied) {
            return "both";
        }
        if (envGuardrailApplied) {
            return "env";
        }
        if (workspaceGuardrailApplied) {
            return "manifest";
        }
        return "none";
    }
}
