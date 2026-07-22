package com.example.agentweb.app.chatrun;

/**
 * Raised when the new run API is called while its rollout switch is disabled.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public class ResumableChatStreamDisabledException extends RuntimeException {

    public ResumableChatStreamDisabledException() {
        super("resumable chat stream is disabled");
    }
}
