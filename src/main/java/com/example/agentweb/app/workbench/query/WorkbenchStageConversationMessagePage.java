package com.example.agentweb.app.workbench.query;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 当前动态 Stage Conversation 的 Owner 侧安全消息投影。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationMessagePage {

    private final String sessionId;
    private final int generation;
    private final long workbenchVersion;
    private final List<MessageView> messages;
    private final Long nextCursor;

    public WorkbenchStageConversationMessagePage(
            String sessionId, int generation, long workbenchVersion,
            List<MessageView> messages) {
        this(sessionId, generation, workbenchVersion, messages, null);
    }

    public WorkbenchStageConversationMessagePage(
            String sessionId, int generation, long workbenchVersion,
            List<MessageView> messages, Long nextCursor) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.messages = Collections.unmodifiableList(
                new ArrayList<MessageView>(
                        Objects.requireNonNull(messages, "messages")));
        if (nextCursor != null
                && (nextCursor.longValue() <= 0L || this.messages.isEmpty()
                || this.messages.get(0).getMessageId()
                != nextCursor.longValue())) {
            throw new IllegalArgumentException(
                    "stage conversation next cursor must identify the oldest page message");
        }
        this.nextCursor = nextCursor;
    }

    /**
     * 单条持久化消息；Run Identifier 只用于关联已恢复的 Run 事件。
     *
     * @author alex
     * @since 2026-08-05
     */
    @Getter
    public static final class MessageView {

        private final long messageId;
        private final String role;
        private final String content;
        private final String timestamp;
        private final String runId;

        public MessageView(
                long messageId, String role, String content,
                String timestamp, String runId) {
            this.messageId = messageId;
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
            this.runId = runId;
        }
    }
}
