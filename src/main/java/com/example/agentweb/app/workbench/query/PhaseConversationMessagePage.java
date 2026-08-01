package com.example.agentweb.app.workbench.query;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 当前 Workbench Phase Conversation 的 Owner 侧消息投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationMessagePage {

    private final String sessionId;
    private final int generation;
    private final long workbenchVersion;
    private final List<MessageView> messages;
    private final Long nextCursor;

    public PhaseConversationMessagePage(
            String sessionId, int generation, long workbenchVersion,
            List<MessageView> messages) {
        this(sessionId, generation, workbenchVersion, messages, null);
    }

    public PhaseConversationMessagePage(
            String sessionId, int generation, long workbenchVersion,
            List<MessageView> messages, Long nextCursor) {
        this.sessionId = sessionId;
        this.generation = generation;
        this.workbenchVersion = workbenchVersion;
        this.messages = Collections.unmodifiableList(new ArrayList<MessageView>(
                Objects.requireNonNull(messages, "messages")));
        if (nextCursor != null
                && (nextCursor.longValue() <= 0L || this.messages.isEmpty()
                || this.messages.get(0).getMessageId()
                != nextCursor.longValue())) {
            throw new IllegalArgumentException(
                    "phase conversation next cursor must identify the oldest page message");
        }
        this.nextCursor = nextCursor;
    }

    /** 单条持久化消息；runId 仅用于前端关联已恢复的 Run 事件。 */
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
