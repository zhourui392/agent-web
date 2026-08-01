package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个阶段会话代际的只读引用；Retire 只结束当前代际，不删除历史消息。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationReference {

    private final String conversationId;
    private final int generation;
    private final OwnerReference createdBy;
    private final Instant createdAt;
    private final Instant retiredAt;

    private PhaseConversationReference(String conversationId, int generation,
                                       OwnerReference createdBy, Instant createdAt,
                                       Instant retiredAt) {
        this.conversationId = DomainText.require(
                conversationId, "phase conversation id", 128);
        if (generation < 0) {
            throw new IllegalArgumentException("conversation generation must not be negative");
        }
        this.generation = generation;
        if (createdBy == null) {
            throw new IllegalArgumentException("conversation creator must not be null");
        }
        this.createdBy = createdBy;
        this.createdAt = DomainText.requireTime(createdAt, "conversation created at");
        this.retiredAt = retiredAt;
        if (retiredAt != null && retiredAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "conversation retired time must not be before created time");
        }
    }

    public static PhaseConversationReference active(String conversationId, int generation,
                                                    OwnerReference createdBy,
                                                    Instant createdAt) {
        return new PhaseConversationReference(
                conversationId, generation, createdBy, createdAt, null);
    }

    public static PhaseConversationReference restore(String conversationId, int generation,
                                                     OwnerReference createdBy, Instant createdAt,
                                                     Instant retiredAt) {
        return new PhaseConversationReference(
                conversationId, generation, createdBy, createdAt, retiredAt);
    }

    public PhaseConversationReference retire(Instant now) {
        Instant retired = DomainText.requireTime(now, "conversation retired at");
        if (retiredAt != null) {
            return this;
        }
        return new PhaseConversationReference(
                conversationId, generation, createdBy, createdAt, retired);
    }

    public boolean isActive() {
        return retiredAt == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PhaseConversationReference)) {
            return false;
        }
        PhaseConversationReference that = (PhaseConversationReference) other;
        return generation == that.generation
                && conversationId.equals(that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, generation);
    }
}
