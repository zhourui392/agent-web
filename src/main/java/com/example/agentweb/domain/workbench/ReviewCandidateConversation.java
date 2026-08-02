package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Review Candidate 生成时捕获的有界当前会话公开投影。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class ReviewCandidateConversation {

    public static final int MAX_MESSAGES = 50;

    private final String conversationId;
    private final int generation;
    private final List<ReviewCandidateMessage> messages;

    private ReviewCandidateConversation(
            String conversationId, int generation,
            List<ReviewCandidateMessage> messages) {
        this.conversationId = DomainText.require(
                conversationId, "review candidate conversation id", 128);
        if (generation < 0) {
            throw new IllegalArgumentException(
                    "review candidate conversation generation must not be negative");
        }
        this.generation = generation;
        this.messages = boundedMessages(messages);
    }

    public static ReviewCandidateConversation capture(
            String conversationId, int generation,
            List<ReviewCandidateMessage> messages) {
        return new ReviewCandidateConversation(
                conversationId, generation, messages);
    }

    private static List<ReviewCandidateMessage> boundedMessages(
            List<ReviewCandidateMessage> values) {
        if (values == null || values.contains(null)) {
            throw new IllegalArgumentException(
                    "review candidate messages must not contain null");
        }
        if (values.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "review candidate accepts at most 50 public messages");
        }
        List<ReviewCandidateMessage> result =
                new ArrayList<ReviewCandidateMessage>(values);
        long previousId = 0L;
        for (ReviewCandidateMessage message : result) {
            if (message.getMessageId() <= previousId) {
                throw new IllegalArgumentException(
                        "review candidate messages must have unique ascending ids");
            }
            previousId = message.getMessageId();
        }
        return Collections.unmodifiableList(result);
    }
}
