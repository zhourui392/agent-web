package com.example.agentweb.app.agentrun;

/**
 * Adds one optional prompt part to a run assembly.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
public interface PromptContributor {

    /**
     * Append contribution into assembly. Implementations must degrade to empty for optional context.
     *
     * @param assembly mutable prompt assembly
     */
    void append(PromptAssembly assembly);
}
