package com.example.agentweb.app.chatrun;

import com.example.agentweb.app.StreamOutputExtractor;
import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.domain.slashcommand.SlashCommandExpander;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatRun 提示词构建契约测试。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
class ChatRunPromptBuilderTest {

    private SlashCommandExpander commandExpander;
    private StreamOutputExtractor outputExtractor;
    private ChatPromptSettings settings;
    private ChatRunPromptBuilder builder;

    @BeforeEach
    void setUp() {
        commandExpander = mock(SlashCommandExpander.class);
        outputExtractor = mock(StreamOutputExtractor.class);
        settings = mock(ChatPromptSettings.class);
        builder = new ChatRunPromptBuilder(commandExpander, outputExtractor, settings);
    }

    @Test
    void resumeIdPresent_shouldNotInjectHistory() {
        ChatRunExecutionContext context = context("resume-1", "question",
                Collections.singletonList(new ChatRunHistoryMessageView("user", "earlier")));
        when(commandExpander.expandIfCommand("/workspace", "question")).thenReturn("question");

        String prompt = builder.prepare(context, "question");

        assertFalse(prompt.contains("<conversation_history>"));
        assertEquals("question", prompt);
    }

    @Test
    void emptyResumeIdWithHistory_shouldInjectUserAndPlainAssistantHistory() {
        List<ChatRunHistoryMessageView> history = Arrays.asList(
                new ChatRunHistoryMessageView("user", "earlier question"),
                new ChatRunHistoryMessageView("assistant", "raw assistant"));
        ChatRunExecutionContext context = context("", "new question", history);
        when(commandExpander.expandIfCommand("/workspace", "new question"))
                .thenReturn("new question");
        when(outputExtractor.extractPlainText("raw assistant"))
                .thenReturn("plain assistant");

        String prompt = builder.prepare(context, "new question");

        assertTrue(prompt.contains("<conversation_history>"));
        assertTrue(prompt.contains("[user]: earlier question"));
        assertTrue(prompt.contains("[assistant]: plain assistant"));
        assertTrue(prompt.contains("<new_user_message>\nnew question\n</new_user_message>"));
    }

    @Test
    void emptyHistory_shouldLeaveFirstMessageUnwrapped() {
        ChatRunExecutionContext context = context(null, "first question",
                Collections.<ChatRunHistoryMessageView>emptyList());
        when(commandExpander.expandIfCommand("/workspace", "first question"))
                .thenReturn("first question");

        String prompt = builder.prepare(context, "first question");

        assertEquals("first question", prompt);
        assertFalse(prompt.contains("<conversation_history>"));
    }

    @Test
    void blankHistoryMessages_shouldBeSkipped() {
        List<ChatRunHistoryMessageView> history = Arrays.asList(
                new ChatRunHistoryMessageView("user", ""),
                new ChatRunHistoryMessageView("assistant", "raw-empty"),
                new ChatRunHistoryMessageView("user", "kept"));
        ChatRunExecutionContext context = context(null, "question", history);
        when(commandExpander.expandIfCommand("/workspace", "question")).thenReturn("question");
        when(outputExtractor.extractPlainText("raw-empty")).thenReturn("");

        String prompt = builder.prepare(context, "question");

        assertTrue(prompt.contains("[user]: kept"));
        assertFalse(prompt.contains("raw-empty"));
        assertEquals(prompt.indexOf("[user]:"), prompt.lastIndexOf("[user]:"));
    }

    @Test
    void enabledFinalInstruction_shouldAppendConfiguredContract() {
        ChatRunExecutionContext context = context(null, "question",
                Collections.<ChatRunHistoryMessageView>emptyList());
        when(commandExpander.expandIfCommand("/workspace", "question")).thenReturn("question");
        when(settings.isFinalAnswerInstructionEnabled()).thenReturn(true);
        when(settings.getFinalAnswerInstruction()).thenReturn("  [最终回答要求]  ");

        String prompt = builder.prepare(context, "question");

        assertEquals("question\n\n---\n[最终回答要求]", prompt);
    }

    @Test
    void recalledInput_shouldNotRepeatSlashExpansion() {
        ChatRunExecutionContext context = context(null, "/recall topic",
                Collections.<ChatRunHistoryMessageView>emptyList());

        String prompt = builder.prepare(context, "augmented prompt");

        assertEquals("augmented prompt", prompt);
        verify(commandExpander, never()).expandIfCommand(anyString(), anyString());
    }

    private ChatRunExecutionContext context(String resumeId, String message,
                                            List<ChatRunHistoryMessageView> history) {
        return new ChatRunExecutionContext("run-1", "session-1", 11L, AgentType.CODEX,
                "/workspace", resumeId, "test", "user-1", message, false, history);
    }
}
