package com.example.agentweb.domain.workbench;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Dynamic Stage Run 的仓内文档与 Stage 上传附件边界。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
public final class VerifiedWorkbenchStageRunAttachmentSet {

    private final List<VerifiedWorkbenchRunAttachment> repositoryDocuments;
    private final List<VerifiedWorkbenchStageUploadedConversationAttachment>
            uploadedAttachments;

    private VerifiedWorkbenchStageRunAttachmentSet(
            List<VerifiedWorkbenchRunAttachment> repositoryDocuments,
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    uploadedAttachments) {
        this.repositoryDocuments =
                VerifiedWorkbenchRunAttachment.immutableList(
                        repositoryDocuments);
        this.uploadedAttachments = immutableUploads(uploadedAttachments);
        requireCombinedLimitAndUniqueness();
    }

    public static VerifiedWorkbenchStageRunAttachmentSet of(
            List<VerifiedWorkbenchRunAttachment> repositoryDocuments,
            List<VerifiedWorkbenchStageUploadedConversationAttachment>
                    uploadedAttachments) {
        return new VerifiedWorkbenchStageRunAttachmentSet(
                repositoryDocuments, uploadedAttachments);
    }

    public static VerifiedWorkbenchStageRunAttachmentSet empty() {
        return of(
                Collections.<VerifiedWorkbenchRunAttachment>emptyList(),
                Collections.<
                        VerifiedWorkbenchStageUploadedConversationAttachment>
                        emptyList());
    }

    public int size() {
        return repositoryDocuments.size() + uploadedAttachments.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    private void requireCombinedLimitAndUniqueness() {
        if (size() > WorkbenchRunAttachmentSelection.MAXIMUM_ATTACHMENTS) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.ATTACHMENT_LIMIT_EXCEEDED,
                    "Dynamic Stage Run accepts at most eight attachments");
        }
        Set<String> identities = new HashSet<String>();
        for (VerifiedWorkbenchRunAttachment attachment
                : repositoryDocuments) {
            DocumentReference reference = attachment.getDocumentReference();
            identities.add("REPOSITORY_DOCUMENT:"
                    + reference.getRepositoryKey() + "/"
                    + reference.getRelativePath());
        }
        for (VerifiedWorkbenchStageUploadedConversationAttachment attachment
                : uploadedAttachments) {
            if (!identities.add(attachment.logicalIdentity())) {
                throw duplicateIdentity();
            }
        }
    }

    private static List<VerifiedWorkbenchStageUploadedConversationAttachment>
            immutableUploads(
                    List<
                            VerifiedWorkbenchStageUploadedConversationAttachment>
                            values) {
        if (values == null) {
            throw new IllegalArgumentException(
                    "Verified Stage uploaded attachments must not be null");
        }
        List<VerifiedWorkbenchStageUploadedConversationAttachment> result =
                new ArrayList<
                        VerifiedWorkbenchStageUploadedConversationAttachment>();
        Set<String> identities = new HashSet<String>();
        for (VerifiedWorkbenchStageUploadedConversationAttachment value
                : values) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Verified Stage uploaded attachments contain null");
            }
            if (!identities.add(value.logicalIdentity())) {
                throw duplicateIdentity();
            }
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static WorkbenchDomainException duplicateIdentity() {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.ATTACHMENT_INVALID,
                "Dynamic Stage Run attachment identities must be unique");
    }
}
