package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import org.springframework.stereotype.Component;

/**
 * Prepares the persisted run input using the existing slash-command, rewind-history and
 * final-answer contracts. This is technical prompt assembly, not run lifecycle logic.
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Component
public class ChatRunPromptBuilder {

    private final SlashCommandExpander commandExpander;
    private final StreamOutputExtractor outputExtractor;
    private final ChatPromptSettings chatPromptSettings;

    public ChatRunPromptBuilder(SlashCommandExpander commandExpander,
                                StreamOutputExtractor outputExtractor,
                                ChatPromptSettings chatPromptSettings) {
        this.commandExpander = commandExpander;
        this.outputExtractor = outputExtractor;
        this.chatPromptSettings = chatPromptSettings;
    }

    public String prepare(ChatRunExecutionContext context, String input) {
        String prompt = input.equals(context.getMessage())
                ? commandExpander.expandIfCommand(context.getWorkingDir(), input)
                : input;
        if (shouldInjectHistory(context)) {
            prompt = historyPrefix(context, prompt);
        }
        return appendFinalAnswerInstruction(prompt);
    }

    private boolean shouldInjectHistory(ChatRunExecutionContext context) {
        return (context.getResumeId() == null || context.getResumeId().trim().isEmpty())
                && !context.getHistory().isEmpty();
    }

    private String historyPrefix(ChatRunExecutionContext context, String currentMessage) {
        StringBuilder result = new StringBuilder();
        result.append("<conversation_history>\n");
        result.append("The following is prior conversation context from a previous session. ");
        result.append("Please consider it as background and respond only to the new user message at the bottom.\n\n");
        for (ChatRunHistoryMessageView message : context.getHistory()) {
            String text = "user".equals(message.getRole())
                    ? message.getContent()
                    : outputExtractor.extractPlainText(message.getContent());
            if (text == null || text.isEmpty()) {
                continue;
            }
            result.append('[').append(message.getRole()).append("]: ")
                    .append(text).append("\n\n");
        }
        result.append("</conversation_history>\n\n");
        result.append("<new_user_message>\n").append(currentMessage)
                .append("\n</new_user_message>");
        return result.toString();
    }

    private String appendFinalAnswerInstruction(String message) {
        if (!chatPromptSettings.isFinalAnswerInstructionEnabled()) {
            return message;
        }
        String instruction = chatPromptSettings.getFinalAnswerInstruction();
        if (instruction == null || instruction.trim().isEmpty()) {
            return message;
        }
        return message + "\n\n---\n" + instruction.trim();
    }
}
