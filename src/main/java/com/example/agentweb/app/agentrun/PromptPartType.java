package com.example.agentweb.app.agentrun;

/**
 * Prompt part category used by prompt assembly observation.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public enum PromptPartType {
    ENV,
    WORKSPACE_CONTEXT,
    KNOWLEDGE_PRE_RECALL,
    HISTORICAL_RAG,
    OUTPUT_INSTRUCTION,
    USER_INPUT
}
