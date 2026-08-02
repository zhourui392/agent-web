package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单次 Run 仓内文档与浏览器上传附件的统一数量和唯一性边界。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
public final class VerifiedWorkbenchRunAttachmentSet {

    public static final int MAXIMUM_ATTACHMENTS = 8;

    private final List<VerifiedWorkbenchRunAttachment> repositoryDocuments;
    private final List<VerifiedUploadedConversationAttachment> uploadedAttachments;

    private VerifiedWorkbenchRunAttachmentSet(
            List<VerifiedWorkbenchRunAttachment> repositoryDocuments,
            List<VerifiedUploadedConversationAttachment> uploadedAttachments) {
        this.repositoryDocuments =
                VerifiedWorkbenchRunAttachment.immutableList(
                        repositoryDocuments);
        this.uploadedAttachments = immutableUploads(uploadedAttachments);
        requireCombinedLimitAndUniqueness();
    }

    public static VerifiedWorkbenchRunAttachmentSet of(
            List<VerifiedWorkbenchRunAttachment> repositoryDocuments,
            List<VerifiedUploadedConversationAttachment> uploadedAttachments) {
        return new VerifiedWorkbenchRunAttachmentSet(
                repositoryDocuments, uploadedAttachments);
    }

    public static VerifiedWorkbenchRunAttachmentSet empty() {
        return of(
                Collections.<VerifiedWorkbenchRunAttachment>emptyList(),
                Collections.<VerifiedUploadedConversationAttachment>emptyList());
    }

    public int size() {
        return repositoryDocuments.size() + uploadedAttachments.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private void requireCombinedLimitAndUniqueness() {
        if (size() > MAXIMUM_ATTACHMENTS) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "workbench run accepts at most eight combined attachments");
        }
        Set<String> identities = new HashSet<String>();
        for (VerifiedWorkbenchRunAttachment attachment : repositoryDocuments) {
            DocumentReference reference = attachment.getDocumentReference();
            identities.add("REPOSITORY_DOCUMENT:"
                    + reference.getRepositoryKey() + "/"
                    + reference.getRelativePath());
        }
        for (VerifiedUploadedConversationAttachment attachment
                : uploadedAttachments) {
            if (!identities.add(attachment.logicalIdentity())) {
                throw new WorkbenchDomainException(
                        WorkbenchErrorCode.ATTACHMENT_INVALID,
                        "workbench run attachment identities must be unique");
            }
        }
    }

    private static List<VerifiedUploadedConversationAttachment> immutableUploads(
            List<VerifiedUploadedConversationAttachment> values) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "verified uploaded attachments must not be null");
        }
        List<VerifiedUploadedConversationAttachment> result =
                new ArrayList<VerifiedUploadedConversationAttachment>();
        Set<String> identities = new HashSet<String>();
        for (VerifiedUploadedConversationAttachment value : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "verified uploaded attachments must not contain null");
            }
            if (!identities.add(value.logicalIdentity())) {
                throw new WorkbenchDomainException(
                        WorkbenchErrorCode.ATTACHMENT_INVALID,
                        "uploaded attachment identities must be unique");
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }
}
