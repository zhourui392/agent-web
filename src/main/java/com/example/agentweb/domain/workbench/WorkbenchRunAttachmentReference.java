package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.CanonicalHashing;
import com.example.agentweb.domain.shared.DomainText;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Workbench Run 客户端提交的类型化附件联合引用。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class WorkbenchRunAttachmentReference {

    private final WorkbenchRunAttachmentType type;
    private final DocumentReference documentReference;
    private final String uploadedAttachmentId;
    private final String contentHash;

    private WorkbenchRunAttachmentReference(
            WorkbenchRunAttachmentType type,
            DocumentReference documentReference,
            String uploadedAttachmentId, String contentHash) {
        if (type == null) {
            throw new IllegalArgumentException(
                    "workbench run attachment type is required");
        }
        this.type = type;
        this.documentReference = documentReference;
        this.uploadedAttachmentId = uploadedAttachmentId;
        this.contentHash = DomainText.requireSha256(
                contentHash, "workbench run attachment content hash");
        requireExactUnion();
    }

    public static WorkbenchRunAttachmentReference of(
            String repositoryKey, String relativePath, String contentHash) {
        return repositoryDocument(repositoryKey, relativePath, contentHash);
    }

    public static WorkbenchRunAttachmentReference repositoryDocument(
            String repositoryKey, String relativePath, String contentHash) {
        return new WorkbenchRunAttachmentReference(
                WorkbenchRunAttachmentType.REPOSITORY_DOCUMENT,
                DocumentReference.of(repositoryKey, relativePath),
                null, contentHash);
    }

    public static WorkbenchRunAttachmentReference uploadedConversation(
            String attachmentId, String contentHash) {
        return new WorkbenchRunAttachmentReference(
                WorkbenchRunAttachmentType.UPLOADED_CONVERSATION,
                null,
                DomainText.require(
                        attachmentId, "uploaded attachment id", 128),
                contentHash);
    }

    public String logicalIdentity() {
        if (type == WorkbenchRunAttachmentType.REPOSITORY_DOCUMENT) {
            return type.name() + ":" + documentReference.getRepositoryKey()
                    + "/" + documentReference.getRelativePath();
        }
        return type.name() + ":" + uploadedAttachmentId;
    }

    public void appendCanonical(StringBuilder canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException(
                    "workbench run attachment canonical target is required");
        }
        if (type == WorkbenchRunAttachmentType.REPOSITORY_DOCUMENT) {
            appendRepositoryDocumentCanonical(canonical);
            return;
        }
        CanonicalHashing.appendFramed(
                canonical, "attachmentType", type.name());
        CanonicalHashing.appendFramed(
                canonical, "uploadedAttachmentId", uploadedAttachmentId);
        CanonicalHashing.appendFramed(
                canonical, "attachmentContentHash", contentHash);
    }

    private void appendRepositoryDocumentCanonical(StringBuilder canonical) {
        CanonicalHashing.appendFramed(
                canonical, "attachmentRepositoryKey",
                documentReference.getRepositoryKey());
        CanonicalHashing.appendFramed(
                canonical, "attachmentRelativePath",
                documentReference.getRelativePath());
        CanonicalHashing.appendFramed(
                canonical, "attachmentContentHash", contentHash);
    }

    public <T> T resolve(Resolver<T> resolver) {
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "workbench attachment resolver is required");
        }
        if (type == WorkbenchRunAttachmentType.REPOSITORY_DOCUMENT) {
            return resolver.repositoryDocument(
                    documentReference, contentHash);
        }
        return resolver.uploadedConversation(
                uploadedAttachmentId, contentHash);
    }

    private void requireExactUnion() {
        boolean repository = documentReference != null;
        boolean uploaded = uploadedAttachmentId != null;
        if (repository == uploaded
                || (type == WorkbenchRunAttachmentType.REPOSITORY_DOCUMENT
                && !repository)
                || (type == WorkbenchRunAttachmentType.UPLOADED_CONVERSATION
                && !uploaded)) {
            throw new IllegalArgumentException(
                    "workbench run attachment union is invalid");
        }
    }

    public interface Resolver<T> {

        T repositoryDocument(
                DocumentReference documentReference, String contentHash);

        T uploadedConversation(
                String attachmentId, String contentHash);
    }
}
