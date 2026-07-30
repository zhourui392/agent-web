package com.example.agentweb.domain.agentrun;

import lombok.Getter;

/**
 * Temporary runtime or environment availability failure.
 *
 * @author alex
 * @since 2026-07-29
 */
@Getter
public class AgentRuntimeUnavailableException extends IllegalStateException {

    private final String code;

    public AgentRuntimeUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }
}
