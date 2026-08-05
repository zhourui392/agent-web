package com.example.agentweb.domain.workbench.stage;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.OwnerReference;
import com.example.agentweb.domain.workbench.WorkbenchDomainException;
import com.example.agentweb.domain.workbench.WorkbenchErrorCode;
import com.example.agentweb.domain.workbench.WorkbenchId;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 将一次动态 Stage Conversation restart 请求绑定到原始结果的幂等收据。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageConversationRestartReceipt {

    private final OwnerReference owner;
    private final String idempotencyKey;
    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
    private final String previousSessionId;
    private final String sessionId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final Instant createdAt;

    private WorkbenchStageConversationRestartReceipt(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        if (owner == null || workbenchId == null) {
            throw new IllegalArgumentException(
                    "Stage restart receipt identity is required");
        }
        this.owner = owner;
        this.idempotencyKey = DomainText.require(
                idempotencyKey,
                "Stage conversation restart idempotency key", 128);
        this.workbenchId = workbenchId;
        this.stageInstanceIdentifier = DomainText.require(
                stageInstanceIdentifier, "Stage Instance identifier", 128);
        this.previousSessionId = DomainText.require(
                previousSessionId, "Previous Stage Session identifier", 128);
        this.sessionId = DomainText.require(
                sessionId, "Stage Session identifier", 128);
        if (this.previousSessionId.equals(this.sessionId)) {
            throw new IllegalArgumentException(
                    "Stage restart receipt Sessions must differ");
        }
        if (conversationGeneration < 1 || workbenchVersion < 0L) {
            throw new IllegalArgumentException(
                    "Stage restart receipt versions are invalid");
        }
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.createdAt = DomainText.requireTime(
                createdAt, "Stage restart receipt creation time");
    }

    public static WorkbenchStageConversationRestartReceipt record(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        return new WorkbenchStageConversationRestartReceipt(
                owner, idempotencyKey, workbenchId,
                stageInstanceIdentifier, previousSessionId, sessionId,
                conversationGeneration, workbenchVersion, createdAt);
    }

    public static WorkbenchStageConversationRestartReceipt restore(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, String stageInstanceIdentifier,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        return record(owner, idempotencyKey, workbenchId,
                stageInstanceIdentifier, previousSessionId, sessionId,
                conversationGeneration, workbenchVersion, createdAt);
    }

    public WorkbenchStageConversationRestartReceipt requireReplay(
            OwnerReference actor, String candidateIdempotencyKey,
            WorkbenchId candidateWorkbenchId,
            String candidateStageInstanceIdentifier) {
        if (!owner.sameIdentityAs(actor)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "Only the receipt Owner can replay a Stage restart");
        }
        String candidateKey = DomainText.require(
                candidateIdempotencyKey,
                "Stage conversation restart idempotency key", 128);
        String candidateStage = DomainText.require(
                candidateStageInstanceIdentifier,
                "Stage Instance identifier", 128);
        if (!idempotencyKey.equals(candidateKey)
                || !workbenchId.equals(candidateWorkbenchId)
                || !stageInstanceIdentifier.equals(candidateStage)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "Stage conversation restart key belongs to another request");
        }
        return this;
    }

    public void requireWorkbenchOwner(OwnerReference workbenchOwner) {
        if (!owner.equals(workbenchOwner)) {
            throw new IllegalArgumentException(
                    "Stage restart receipt Owner must match Workbench Owner");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WorkbenchStageConversationRestartReceipt)) {
            return false;
        }
        WorkbenchStageConversationRestartReceipt that =
                (WorkbenchStageConversationRestartReceipt) other;
        return conversationGeneration == that.conversationGeneration
                && workbenchVersion == that.workbenchVersion
                && owner.equals(that.owner)
                && idempotencyKey.equals(that.idempotencyKey)
                && workbenchId.equals(that.workbenchId)
                && stageInstanceIdentifier.equals(
                that.stageInstanceIdentifier)
                && previousSessionId.equals(that.previousSessionId)
                && sessionId.equals(that.sessionId)
                && createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, idempotencyKey, workbenchId,
                stageInstanceIdentifier, previousSessionId, sessionId,
                conversationGeneration, workbenchVersion, createdAt);
    }
}
