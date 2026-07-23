package com.example.agentweb.domain.harness;

/**
 * Harness 首版支持的 Agent Runtime。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public enum AgentRuntime {
    CODEX,
    CLAUDE;

    public static AgentRuntime from(String value) {
        try {
            return AgentRuntime.valueOf(DomainText.require(value, "agent runtime", 60)
                    .toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new CapabilityResolutionException("RUNTIME_UNSUPPORTED",
                    "unsupported Harness runtime: " + value);
        }
    }
}
