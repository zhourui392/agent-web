package com.example.agentweb.domain.agentrun;

import lombok.Getter;

/**
 * Permanent product-policy violation for an agent selection.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public class AgentPolicyViolationException extends IllegalArgumentException {

    private final String code;

    public AgentPolicyViolationException(String code, String message) {
        super(message);
        this.code = code;
    }
}
