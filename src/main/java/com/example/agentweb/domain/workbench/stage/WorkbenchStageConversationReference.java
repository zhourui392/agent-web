package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 一个动态 Stage 会话代际的不可变引用。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationReference {

    private final String conversationId;
    private final int generation;
    private final OwnerReference createdBy;
    private final Instant createdAt;
    private final Instant retiredAt;

    private WorkbenchStageConversationReference(
            String conversationId, int generation,
            OwnerReference createdBy, Instant createdAt, Instant retiredAt) {
        this.conversationId = DomainText.require(
                conversationId, "Stage conversation identifier", 128);
        if (generation < 0 || createdBy == null) {
            throw new IllegalArgumentException(
                    "Stage conversation generation and creator are required");
        }
        this.generation = generation;
        this.createdBy = createdBy;
        this.createdAt = DomainText.requireTime(
                createdAt, "Stage conversation creation time");
        if (retiredAt != null && retiredAt.isBefore(this.createdAt)) {
            throw new IllegalArgumentException(
                    "Stage conversation retirement cannot precede creation");
        }
        this.retiredAt = retiredAt;
    }

    public static WorkbenchStageConversationReference active(
            String conversationId, int generation,
            OwnerReference createdBy, Instant createdAt) {
        return new WorkbenchStageConversationReference(
                conversationId, generation, createdBy, createdAt, null);
    }

    public static WorkbenchStageConversationReference restore(
            String conversationId, int generation,
            OwnerReference createdBy, Instant createdAt, Instant retiredAt) {
        return new WorkbenchStageConversationReference(
                conversationId, generation, createdBy, createdAt, retiredAt);
    }

    public WorkbenchStageConversationReference retire(Instant now) {
        Instant retirementTime = DomainText.requireTime(
                now, "Stage conversation retirement time");
        if (retirementTime.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Stage conversation retirement cannot precede creation");
        }
        if (retiredAt != null) {
            return this;
        }
        return new WorkbenchStageConversationReference(
                conversationId, generation, createdBy, createdAt,
                retirementTime);
    }

    public boolean isActive() {
        return retiredAt == null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkbenchStageConversationReference)) {
            return false;
        }
        WorkbenchStageConversationReference that =
                (WorkbenchStageConversationReference) other;
        return generation == that.generation
                && conversationId.equals(that.conversationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, generation);
    }
}
