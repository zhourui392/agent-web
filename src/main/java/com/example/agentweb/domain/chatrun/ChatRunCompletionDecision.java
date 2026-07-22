package com.example.agentweb.domain.chatrun;

/**
 * Domain decision for translating a finished CLI process into a run lifecycle outcome.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public enum ChatRunCompletionDecision {
    SUCCEED,
    FAIL,
    CANCEL
}
