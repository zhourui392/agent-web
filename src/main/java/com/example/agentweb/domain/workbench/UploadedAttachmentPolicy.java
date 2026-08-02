package com.example.agentweb.domain.workbench;

import java.time.Duration;

/**
 * 上传附件数量、时效与单次 Runtime 保留边界。
 *
 * @author alex
 * @since 2026-08-01
 */
public final class UploadedAttachmentPolicy {

    private final long maximumBytes;
    private final int maximumAvailablePerConversation;
    private final Duration availableTtl;
    private final Duration boundRetention;

    private UploadedAttachmentPolicy(
            long maximumBytes, int maximumAvailablePerConversation,
            Duration availableTtl, Duration boundRetention) {
        if (maximumBytes < 1L || maximumBytes > Integer.MAX_VALUE
                || maximumAvailablePerConversation < 1
                || availableTtl == null || availableTtl.isZero()
                || availableTtl.isNegative()
                || boundRetention == null || boundRetention.isZero()
                || boundRetention.isNegative()) {
            throw new IllegalArgumentException(
                    "uploaded attachment policy is invalid");
        }
        this.maximumBytes = maximumBytes;
        this.maximumAvailablePerConversation =
                maximumAvailablePerConversation;
        this.availableTtl = availableTtl;
        this.boundRetention = boundRetention;
    }

    public static UploadedAttachmentPolicy standard(
            long maximumBytes, int maximumAvailablePerConversation,
            Duration availableTtl, Duration boundRetention) {
        return new UploadedAttachmentPolicy(
                maximumBytes, maximumAvailablePerConversation,
                availableTtl, boundRetention);
    }

    public void requireSize(long size) {
        if (size < 1L) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_INVALID,
                    "uploaded attachment must not be empty");
        }
        if (size > maximumBytes) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_TOO_LARGE,
                    "uploaded attachment exceeds the configured size limit");
        }
    }

    public void requireAvailableQuota(long currentAvailableCount) {
        if (currentAvailableCount < 0L) {
            throw new IllegalArgumentException(
                    "uploaded attachment count must not be negative");
        }
        if (currentAvailableCount >= maximumAvailablePerConversation) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "uploaded attachment conversation quota is exhausted");
        }
    }

    public Duration getAvailableTtl() {
        return availableTtl;
    }

    public Duration getBoundRetention() {
        return boundRetention;
    }

    public long getMaximumBytes() {
        return maximumBytes;
    }

    public int getMaximumAvailablePerConversation() {
        return maximumAvailablePerConversation;
    }
}
