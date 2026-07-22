package com.example.agentweb.app.chatrun;

/**
 * Application port for the technical prompt contract configured by Infrastructure.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
public interface ChatPromptSettings {

    boolean isFinalAnswerInstructionEnabled();

    String getFinalAnswerInstruction();
}
