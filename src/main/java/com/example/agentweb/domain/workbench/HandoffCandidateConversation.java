package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Candidate 生成时一次性捕获的有界公开会话投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class HandoffCandidateConversation {

    public static final int MAX_MESSAGES = 50;

    private final String conversationId;
    private final int generation;
    private final List<HandoffCandidateMessage> messages;

    private HandoffCandidateConversation(
            String conversationId, int generation,
            List<HandoffCandidateMessage> messages) {
        this.conversationId = DomainText.require(
                conversationId, "handoff candidate conversation id", 128);
        if (generation < 0) {
            throw new IllegalArgumentException(
                    "handoff candidate conversation generation must not be negative");
        }
        this.generation = generation;
        this.messages = copyBoundedMessages(messages);
    }

    public static HandoffCandidateConversation capture(
            String conversationId, int generation,
            List<HandoffCandidateMessage> messages) {
        return new HandoffCandidateConversation(
                conversationId, generation, messages);
    }

    private static List<HandoffCandidateMessage> copyBoundedMessages(
            List<HandoffCandidateMessage> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(
                    "handoff candidate messages must not contain null");
        }
        if (values.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "handoff candidate accepts at most 50 public messages");
        }
        List<HandoffCandidateMessage> result =
                new ArrayList<HandoffCandidateMessage>(values);
        Set<Long> identities = new HashSet<Long>();
        long previousId = 0L;
        for (HandoffCandidateMessage message : result) {
            if (message.getMessageId() <= previousId
                    || !identities.add(Long.valueOf(message.getMessageId()))) {
                throw new IllegalArgumentException(
                        "handoff candidate messages must have unique ascending ids");
            }
            previousId = message.getMessageId();
        }
        return Collections.unmodifiableList(result);
    }
}
