package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import lombok.Getter;

import java.text.Normalizer;
import java.time.Instant;

/**
 * 浏览器上传到受控临时存储的 Dynamic Stage 会话附件聚合。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class WorkbenchStageUploadedConversationAttachment {

    private final String attachmentId;
    private final OwnerReference owner;
    private final WorkbenchId workbenchId;
    private final String stageInstanceIdentifier;
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

    private WorkbenchStageUploadedConversationAttachment(
            String attachmentId,
            WorkbenchStageUploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey,
            UploadedConversationAttachmentStatus status,
            String boundRunId, Instant createdAt, Instant expiresAt,
            Instant updatedAt, long version) {
        this.attachmentId = DomainText.require(
                attachmentId, "Stage uploaded attachment identifier", 128);
        if (binding == null || status == null || size < 1L || version < 0L) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment required facts are invalid");
        }
        this.owner = binding.getOwner();
        this.workbenchId = binding.getWorkbenchId();
        this.stageInstanceIdentifier = binding.getStageInstanceIdentifier();
        this.conversationId = binding.getConversationId();
        this.conversationGeneration = binding.getConversationGeneration();
        this.displayName = requireDisplayName(displayName);
        this.mediaType = DomainText.require(
                mediaType, "Stage uploaded attachment media type", 160);
        this.size = size;
        this.contentHash = DomainText.requireSha256(
                contentHash, "Stage uploaded attachment content Hash");
        this.storageKey = DomainText.requireSha256(
                storageKey, "Stage uploaded attachment storage key");
        this.status = status;
        this.boundRunId = normalizeBoundRun(status, boundRunId);
        this.createdAt = DomainText.requireTime(
                createdAt, "Stage uploaded attachment creation time");
        this.expiresAt = DomainText.requireTime(
                expiresAt, "Stage uploaded attachment expiry");
        this.updatedAt = DomainText.requireTime(
                updatedAt, "Stage uploaded attachment update time");
        if (expiresAt.isBefore(createdAt) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment times are inconsistent");
        }
        this.version = version;
    }

    public static WorkbenchStageUploadedConversationAttachment upload(
            String attachmentId,
            WorkbenchStageUploadedAttachmentBinding binding,
            String displayName, String clientMediaType,
            UploadedAttachmentContentSignature contentSignature,
            long size, String contentHash, String storageKey,
            UploadedAttachmentPolicy policy, Instant now) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment policy is required");
        }
        Instant createdAt = DomainText.requireTime(
                now, "Stage uploaded attachment creation time");
        policy.requireSize(size);
        String safeName = requireDisplayName(displayName);
        UploadedAttachmentMediaType trusted =
                UploadedAttachmentMediaType.requireTrusted(
                        safeName, clientMediaType, contentSignature);
        return new WorkbenchStageUploadedConversationAttachment(
                attachmentId, binding, safeName, trusted.getMediaType(),
                size, contentHash, storageKey,
                UploadedConversationAttachmentStatus.AVAILABLE, null,
                createdAt, createdAt.plus(policy.getAvailableTtl()),
                createdAt, 0L);
    }

    public static WorkbenchStageUploadedConversationAttachment restore(
            String attachmentId,
            WorkbenchStageUploadedAttachmentBinding binding,
            String displayName, String mediaType, long size,
            String contentHash, String storageKey,
            UploadedConversationAttachmentStatus status,
            String boundRunId, Instant createdAt, Instant expiresAt,
            Instant updatedAt, long version) {
        return new WorkbenchStageUploadedConversationAttachment(
                attachmentId, binding, displayName, mediaType, size,
                contentHash, storageKey, status, boundRunId,
                createdAt, expiresAt, updatedAt, version);
    }

    public VerifiedWorkbenchStageUploadedConversationAttachment verifyForRun(
            WorkbenchStageUploadedAttachmentBinding expectedBinding,
            String requestedContentHash, Instant now) {
        String requestedHash = DomainText.requireSha256(
                requestedContentHash,
                "Stage uploaded attachment requested content Hash");
        Instant observedAt = DomainText.requireTime(
                now, "Stage uploaded attachment verification time");
        if (expectedBinding == null
                || !binding().equals(expectedBinding)
                || status != UploadedConversationAttachmentStatus.AVAILABLE
                || !contentHash.equals(requestedHash)
                || !observedAt.isBefore(expiresAt)) {
            throw unavailable();
        }
        return VerifiedWorkbenchStageUploadedConversationAttachment.from(this);
    }

    public boolean bindToRun(
            VerifiedWorkbenchStageUploadedConversationAttachment verified,
            String runId, Instant now, UploadedAttachmentPolicy policy) {
        String candidateRunId = DomainText.require(
                runId, "Stage uploaded attachment bound Run identifier", 128);
        Instant boundAt = DomainText.requireTime(
                now, "Stage uploaded attachment bound time");
        if (policy == null) {
            throw new IllegalArgumentException(
                    "Stage uploaded attachment policy is required");
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
                runId, "Stage uploaded attachment terminal Run identifier", 128);
        Instant terminalAt = DomainText.requireTime(
                now, "Stage uploaded attachment terminal time");
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
            WorkbenchStageUploadedAttachmentBinding expectedBinding,
            Instant now) {
        Instant cancelledAt = DomainText.requireTime(
                now, "Stage uploaded attachment cancellation time");
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
                now, "Stage uploaded attachment cleanup time");
        return status == UploadedConversationAttachmentStatus.RELEASE_PENDING
                || !observedAt.isBefore(expiresAt);
    }

    public void requireCleanupAt(Instant now) {
        if (!requiresCleanupAt(now)) {
            throw new IllegalStateException(
                    "Stage uploaded attachment is not eligible for cleanup");
        }
    }

    public void requireOwnedBy(OwnerReference actor) {
        if (!owner.sameIdentityAs(actor)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
    }

    public WorkbenchStageUploadedAttachmentBinding binding() {
        return new WorkbenchStageUploadedAttachmentBinding(
                owner, workbenchId, stageInstanceIdentifier,
                conversationId, conversationGeneration);
    }

    private static String requireDisplayName(String value) {
        if (value == null) {
            throw invalid("Stage uploaded attachment display name is required");
        }
        String normalized = Normalizer.normalize(
                value, Normalizer.Form.NFKC).trim();
        if (normalized.isEmpty() || normalized.length() > 255
                || normalized.indexOf('/') >= 0
                || normalized.indexOf('\\') >= 0
                || ".".equals(normalized) || "..".equals(normalized)) {
            throw invalid("Stage uploaded attachment display name is invalid");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw invalid(
                        "Stage uploaded attachment display name is invalid");
            }
        }
        return normalized;
    }

    private static String normalizeBoundRun(
            UploadedConversationAttachmentStatus status,
            String boundRunId) {
        if (status == UploadedConversationAttachmentStatus.BOUND
                || status == UploadedConversationAttachmentStatus.RELEASE_PENDING
                && boundRunId != null) {
            return DomainText.require(
                    boundRunId,
                    "Stage uploaded attachment bound Run identifier", 128);
        }
        if (boundRunId != null) {
            throw new IllegalArgumentException(
                    "Available Stage uploaded attachment must not have a Run");
        }
        return null;
    }

    private static WorkbenchDomainException invalid(String message) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_INVALID, message);
    }

    private static WorkbenchDomainException unavailable() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_UNAVAILABLE,
                "Stage uploaded attachment is unavailable");
    }

    @Override
    public String toString() {
        return "WorkbenchStageUploadedConversationAttachment{attachmentId, "
                + "owner, workbenchId, stageInstanceIdentifier, "
                + "conversationGeneration, displayName, mediaType, size, "
                + "contentHash, status, boundRunId, expiresAt, version}";
    }
}
