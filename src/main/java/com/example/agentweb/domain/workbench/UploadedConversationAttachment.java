package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.text.Normalizer;
import java.time.Instant;

/**
 * 浏览器上传到受控临时存储的会话附件聚合。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class UploadedConversationAttachment {

    private final String attachmentId;
    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final WorkbenchPhase phase;
    private final String conversationId;
    private final int conversationGeneration;
    private final String displayName;
    private final String mediaType;
    private final long size;
    private final String contentHash;
    private final String storageKey;
    private final Instant createdAt;
    private UploadedConversationAttachmentStatus status;
    private String boundRunId;
    private Instant expiresAt;
    private Instant updatedAt;
    private long version;

    private UploadedConversationAttachment(
            String attachmentId, UploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey,
            UploadedConversationAttachmentStatus status,
            String boundRunId, Instant createdAt, Instant expiresAt,
            Instant updatedAt, long version) {
        this.attachmentId = DomainText.require(
                attachmentId, "uploaded attachment id", 128);
        if (binding == null || status == null || size < 1L || version < 0L) {
            throw new IllegalArgumentException(
                    "uploaded attachment required facts are invalid");
        }
        this.owner = binding.getOwner();
        this.workbenchId = binding.getWorkbenchId();
        this.phase = binding.getPhase();
        this.conversationId = binding.getConversationId();
        this.conversationGeneration = binding.getConversationGeneration();
        this.displayName = requireDisplayName(displayName);
        this.mediaType = DomainText.require(
                mediaType, "uploaded attachment media type", 160);
        this.size = size;
        this.contentHash = DomainText.requireSha256(
                contentHash, "uploaded attachment content hash");
        this.storageKey = DomainText.requireSha256(
                storageKey, "uploaded attachment storage key");
        this.status = status;
        this.boundRunId = normalizeBoundRun(status, boundRunId);
        this.createdAt = DomainText.requireTime(
                createdAt, "uploaded attachment created at");
        this.expiresAt = DomainText.requireTime(
                expiresAt, "uploaded attachment expiry");
        this.updatedAt = DomainText.requireTime(
                updatedAt, "uploaded attachment updated at");
        if (expiresAt.isBefore(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "uploaded attachment times are inconsistent");
        }
        this.version = version;
    }

    public static UploadedConversationAttachment upload(
            String attachmentId, UploadedAttachmentBinding binding,
            String displayName, String clientMediaType,
            UploadedAttachmentContentSignature contentSignature,
            long size, String contentHash, String storageKey,
            UploadedAttachmentPolicy policy, Instant now) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment policy is required");
        }
        Instant createdAt = DomainText.requireTime(
                now, "uploaded attachment created at");
        policy.requireSize(size);
        String safeName = requireDisplayName(displayName);
        UploadedAttachmentMediaType trusted =
                UploadedAttachmentMediaType.requireTrusted(
                        safeName, clientMediaType, contentSignature);
        return new UploadedConversationAttachment(
                attachmentId, binding, safeName, trusted.getMediaType(), size,
                contentHash, storageKey,
                UploadedConversationAttachmentStatus.AVAILABLE, null,
                createdAt, createdAt.plus(policy.getAvailableTtl()),
                createdAt, 0L);
    }

    public static UploadedConversationAttachment restore(
            String attachmentId, UploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey,
            UploadedConversationAttachmentStatus status,
            String boundRunId, Instant createdAt, Instant expiresAt,
            Instant updatedAt, long version) {
        return new UploadedConversationAttachment(
                attachmentId, binding, displayName, mediaType, size,
                contentHash, storageKey, status, boundRunId,
                createdAt, expiresAt, updatedAt, version);
    }

    public VerifiedUploadedConversationAttachment verifyForRun(
            UploadedAttachmentBinding expectedBinding,
            String requestedContentHash, Instant now) {
        String requestedHash = DomainText.requireSha256(
                requestedContentHash,
                "uploaded attachment requested content hash");
        Instant observedAt = DomainText.requireTime(
                now, "uploaded attachment verification time");
        if (expectedBinding == null
                || !binding().equals(expectedBinding)
                || status != UploadedConversationAttachmentStatus.AVAILABLE
                || !contentHash.equals(requestedHash)
                || !observedAt.isBefore(expiresAt)) {
            throw unavailable();
        }
        return VerifiedUploadedConversationAttachment.from(this);
    }

    public boolean bindToRun(
            VerifiedUploadedConversationAttachment verified,
            String runId, Instant now, UploadedAttachmentPolicy policy) {
        String candidateRunId = DomainText.require(
                runId, "uploaded attachment bound run id", 128);
        Instant boundAt = DomainText.requireTime(
                now, "uploaded attachment bound at");
        if (policy == null) {
            throw new IllegalArgumentException(
                    "uploaded attachment policy is required");
        }
        if (status == UploadedConversationAttachmentStatus.BOUND
                && candidateRunId.equals(boundRunId)) {
            return false;
        }
        if (status != UploadedConversationAttachmentStatus.AVAILABLE
                || !boundAt.isBefore(expiresAt)) {
            throw unavailable();
        }
        verified.requireExactAttachment(this);
        status = UploadedConversationAttachmentStatus.BOUND;
        boundRunId = candidateRunId;
        expiresAt = boundAt.plus(policy.getBoundRetention());
        updatedAt = boundAt;
        version++;
        return true;
    }

    public boolean releaseAfterTerminal(String runId, Instant now) {
        String candidateRunId = DomainText.require(
                runId, "uploaded attachment terminal run id", 128);
        Instant terminalAt = DomainText.requireTime(
                now, "uploaded attachment terminal time");
        if (status == UploadedConversationAttachmentStatus.RELEASE_PENDING
                && candidateRunId.equals(boundRunId)) {
            return false;
        }
        if (status != UploadedConversationAttachmentStatus.BOUND
                || !candidateRunId.equals(boundRunId)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        status = UploadedConversationAttachmentStatus.RELEASE_PENDING;
        expiresAt = terminalAt;
        updatedAt = terminalAt;
        version++;
        return true;
    }

    public boolean cancelAvailable(
            UploadedAttachmentBinding expectedBinding, Instant now) {
        Instant cancelledAt = DomainText.requireTime(
                now, "uploaded attachment cancelled at");
        if (expectedBinding == null
                || !binding().equals(expectedBinding)
                || status != UploadedConversationAttachmentStatus.AVAILABLE) {
            throw unavailable();
        }
        status = UploadedConversationAttachmentStatus.RELEASE_PENDING;
        expiresAt = cancelledAt;
        updatedAt = cancelledAt;
        version++;
        return true;
    }

    public boolean requiresCleanupAt(Instant now) {
        Instant observedAt = DomainText.requireTime(
                now, "uploaded attachment cleanup time");
        return status == UploadedConversationAttachmentStatus.RELEASE_PENDING
                || !observedAt.isBefore(expiresAt);
    }

    public void requireCleanupAt(Instant now) {
        if (!requiresCleanupAt(now)) {
            throw new IllegalStateException(
                    "uploaded attachment is not eligible for cleanup");
        }
    }

    public void requireOwnedBy(OwnerReference actor) {
        if (!owner.sameIdentityAs(actor)) {
            throw unavailable();
        }
    }

    public UploadedAttachmentBinding binding() {
        return new UploadedAttachmentBinding(
                owner, workbenchId, phase, conversationId,
                conversationGeneration);
    }

    private static String requireDisplayName(String value) {
        if (value == null) {
            throw invalid("uploaded attachment display name is required");
        }
        String normalized = Normalizer.normalize(
                value, Normalizer.Form.NFKC).trim();
        if (normalized.isEmpty() || normalized.length() > 255
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || ".".equals(normalized) || "..".equals(normalized)) {
            throw invalid("uploaded attachment display name is invalid");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw invalid("uploaded attachment display name is invalid");
            }
        }
        return normalized;
    }

    private static String normalizeBoundRun(
            UploadedConversationAttachmentStatus status,
            String boundRunId) {
        if (status != UploadedConversationAttachmentStatus.BOUND) {
            if (boundRunId != null) {
                if (status
                        == UploadedConversationAttachmentStatus.RELEASE_PENDING) {
                    return DomainText.require(
                            boundRunId,
                            "uploaded attachment bound run id", 128);
                }
                throw new IllegalArgumentException(
                        "available uploaded attachment must not have a run");
            }
            return null;
        }
        return DomainText.require(
                boundRunId, "uploaded attachment bound run id", 128);
    }

    private static WorkbenchDomainException invalid(String message) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_INVALID, message);
    }

    private static WorkbenchDomainException unavailable() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                "uploaded attachment is unavailable");
    }

    @Override
    public String toString() {
        return "UploadedConversationAttachment{attachmentId, owner, workbenchId, "
                + "phase, conversationGeneration, displayName, mediaType, size, "
                + "contentHash, status, boundRunId, expiresAt, version}";
    }
}
