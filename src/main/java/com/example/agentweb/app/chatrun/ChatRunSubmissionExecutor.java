package com.example.agentweb.app.chatrun;

import java.util.function.Supplier;

/**
 * Executes one submission command inside its serialized transaction boundary.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@FunctionalInterface
public interface ChatRunSubmissionExecutor {

    ChatRunSubmission execute(Supplier<ChatRunSubmission> action);
}
