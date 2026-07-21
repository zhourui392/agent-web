package com.example.agentweb.app.agentrun;

import lombok.Getter;

/**
 * Workspace-local guardrail that may only tighten platform/environment constraints.
 *
 * @author zhourui(V33215020)
 * @since 2026-06-13
 */
@Getter
public class WorkspaceGuardrail {

    private final boolean readonly;
    private final String prompt;

    public WorkspaceGuardrail(boolean readonly, String prompt) {
        this.readonly = readonly;
        this.prompt = prompt;
    }
}
