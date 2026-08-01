package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.time.Instant;
import java.util.Objects;

/**
 * 将一次 Phase Conversation restart 请求绑定到原始代际结果的幂等收据。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class PhaseConversationRestartReceipt {

    private final OwnerReference owner;
    private final String idempotencyKey;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String previousSessionId;
    private final String sessionId;
    private final int conversationGeneration;
    private final long workbenchVersion;
    private final Instant createdAt;

    private PhaseConversationRestartReceipt(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        if (owner == null || workbenchId == null || phase == null) {
            throw new IllegalArgumentException("restart receipt identity is required");
        }
        this.owner = owner;
        this.idempotencyKey = DomainText.require(
                idempotencyKey, "phase conversation restart idempotency key", 128);
        this.workbenchId = workbenchId;
        this.phase = phase;
        this.previousSessionId = DomainText.require(
                previousSessionId, "previous phase session id", 128);
        this.sessionId = DomainText.require(sessionId, "phase session id", 128);
        if (this.previousSessionId.equals(this.sessionId)) {
            throw new IllegalArgumentException("restart receipt sessions must be different");
        }
        if (conversationGeneration < 1) {
            throw new IllegalArgumentException("restart conversation generation must be positive");
        }
        if (workbenchVersion < 0L) {
            throw new IllegalArgumentException("restart workbench version must not be negative");
        }
        this.conversationGeneration = conversationGeneration;
        this.workbenchVersion = workbenchVersion;
        this.createdAt = DomainText.requireTime(createdAt, "restart receipt time");
    }

    public static PhaseConversationRestartReceipt record(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        return new PhaseConversationRestartReceipt(
                owner, idempotencyKey, workbenchId, phase,
                previousSessionId, sessionId, conversationGeneration,
                workbenchVersion, createdAt);
    }

    public static PhaseConversationRestartReceipt restore(
            OwnerReference owner, String idempotencyKey,
            WorkbenchId workbenchId, WorkbenchPhase phase,
            String previousSessionId, String sessionId,
            int conversationGeneration, long workbenchVersion,
            Instant createdAt) {
        return record(
                owner, idempotencyKey, workbenchId, phase,
                previousSessionId, sessionId, conversationGeneration,
                workbenchVersion, createdAt);
    }

    public PhaseConversationRestartReceipt requireReplay(
            OwnerReference actor, String candidateIdempotencyKey,
            WorkbenchId candidateWorkbenchId, WorkbenchPhase candidatePhase) {
        if (!owner.sameIdentityAs(actor)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.OWNER_REQUIRED,
                    "only the receipt owner can replay a phase conversation restart");
        }
        String candidateKey = DomainText.require(
                candidateIdempotencyKey,
                "phase conversation restart idempotency key", 128);
        if (!idempotencyKey.equals(candidateKey)
                || !workbenchId.equals(candidateWorkbenchId)
                || phase != candidatePhase) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.IDEMPOTENCY_CONFLICT,
                    "phase conversation restart key belongs to another request");
        }
        return this;
    }

    public void requireWorkbenchOwner(OwnerReference workbenchOwner) {
        if (!owner.equals(workbenchOwner)) {
            throw new IllegalArgumentException(
                    "restart receipt owner must match referenced workbench owner");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PhaseConversationRestartReceipt)) {
            return false;
        }
        PhaseConversationRestartReceipt that = (PhaseConversationRestartReceipt) other;
        return conversationGeneration == that.conversationGeneration
                && workbenchVersion == that.workbenchVersion
                && owner.equals(that.owner)
                && idempotencyKey.equals(that.idempotencyKey)
                && workbenchId.equals(that.workbenchId)
                && phase == that.phase
                && previousSessionId.equals(that.previousSessionId)
                && sessionId.equals(that.sessionId)
                && createdAt.equals(that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, idempotencyKey, workbenchId, phase,
                previousSessionId, sessionId, conversationGeneration,
                workbenchVersion, createdAt);
    }
}
