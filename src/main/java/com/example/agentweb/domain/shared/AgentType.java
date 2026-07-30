package com.example.agentweb.domain.shared;

/**
 * Supported agent identities. Product exposure and runtime availability belong to AgentCatalog.
 *
 * @author alex
 */
public enum AgentType {
    /** OpenAI Codex CLI (codex exec --json). */
    CODEX,
    /** Anthropic Claude Code CLI. */
    CLAUDE,
    /** In-process diagnosis engine backed by agentkit-agent-diagnosis. */
    NATIVE;

    /**
     * Parse a stable agent identity without applying product-selection policy.
     *
     * @param input external identity
     * @return known agent type
     */
    public static AgentType parseKnown(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("agentType is blank");
        }
        try {
            return AgentType.valueOf(input.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown agentType: " + input, ex);
        }
    }

}
