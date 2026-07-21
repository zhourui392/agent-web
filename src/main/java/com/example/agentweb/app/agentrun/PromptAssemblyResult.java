package com.example.agentweb.app.agentrun;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Final assembled prompt plus zero-schema compatible observation fields.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class PromptAssemblyResult {

    private final String prompt;
    private final String promptHash;
    private final List<PromptPart> parts;
    private final Boolean historicalRagUsed;
    private final String historicalRagChunkIdsJson;
    private final List<String> workspaceKnowledgeHits;
    private final List<String> workspaceContextDocs;
    private final List<RecallContribution> recallContributions;
    private final String guardrailSource;

    public PromptAssemblyResult(String prompt,
                                String promptHash,
                                List<PromptPart> parts,
                                Boolean historicalRagUsed,
                                String historicalRagChunkIdsJson,
                                List<String> workspaceKnowledgeHits,
                                String guardrailSource) {
        this(prompt, promptHash, parts, historicalRagUsed, historicalRagChunkIdsJson,
                workspaceKnowledgeHits, Collections.<String>emptyList(),
                Collections.<RecallContribution>emptyList(), guardrailSource);
    }

    public PromptAssemblyResult(String prompt,
                                String promptHash,
                                List<PromptPart> parts,
                                Boolean historicalRagUsed,
                                String historicalRagChunkIdsJson,
                                List<String> workspaceKnowledgeHits,
                                List<String> workspaceContextDocs,
                                List<RecallContribution> recallContributions,
                                String guardrailSource) {
        this.prompt = prompt;
        this.promptHash = promptHash;
        this.parts = parts == null ? Collections.<PromptPart>emptyList()
                : Collections.unmodifiableList(new ArrayList<PromptPart>(parts));
        this.historicalRagUsed = historicalRagUsed;
        this.historicalRagChunkIdsJson = historicalRagChunkIdsJson;
        this.workspaceKnowledgeHits = workspaceKnowledgeHits == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(workspaceKnowledgeHits));
        this.workspaceContextDocs = workspaceContextDocs == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(workspaceContextDocs));
        this.recallContributions = recallContributions == null
                ? Collections.<RecallContribution>emptyList()
                : Collections.unmodifiableList(new ArrayList<RecallContribution>(recallContributions));
        this.guardrailSource = guardrailSource;
    }
}
