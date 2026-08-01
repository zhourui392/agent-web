package com.example.agentweb.domain.workbench;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workspace.RepositoryScope;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Workbench Run 准备阶段从冻结 Scope 重新读取后形成的可信附件事实。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class VerifiedWorkbenchRunAttachment {

    public static final int MAXIMUM_ATTACHMENTS = 8;

    private final DocumentReference documentReference;
    private final String contentVersion;
    private final String mediaType;
    private final long size;

    private VerifiedWorkbenchRunAttachment(
            DocumentReference documentReference, String contentVersion,
            String mediaType, long size) {
        this.documentReference = documentReference;
        this.contentVersion = contentVersion;
        this.mediaType = mediaType;
        this.size = size;
    }

    public static VerifiedWorkbenchRunAttachment verify(
            DocumentReference requestedReference,
            String requestedContentHash,
            DocumentReference observedReference,
            String observedContentVersion,
            String observedMediaType,
            long observedSize,
            boolean observedDeleted) {
        if (requestedReference == null || observedReference == null) {
            throw new IllegalArgumentException(
                    "workbench run attachment references must not be null");
        }
        String requestedHash = DomainText.requireSha256(
                requestedContentHash,
                "workbench run attachment requested content hash");
        String observedVersion = DomainText.requireSha256(
                observedContentVersion,
                "workbench run attachment observed content version");
        String mediaType = DomainText.require(
                observedMediaType,
                "workbench run attachment observed media type", 160);
        if (observedSize < 0L || observedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "workbench run attachment observed size must be bounded");
        }
        if (!requestedReference.equals(observedReference)) {
            throw WorkbenchDomainException.runBindingCorrupted();
        }
        if (observedDeleted || !requestedHash.equals(observedVersion)) {
            throw new WorkbenchDomainException(
                    WorkbenchErrorCode.VERSION_CONFLICT,
                    "workbench run attachment changed after selection");
        }
        return new VerifiedWorkbenchRunAttachment(
                requestedReference, observedVersion, mediaType, observedSize);
    }

    public static VerifiedWorkbenchRunAttachment restore(
            DocumentReference documentReference, String contentVersion,
            String mediaType, long size) {
        return verify(
                documentReference, contentVersion,
                documentReference, contentVersion,
                mediaType, size, false);
    }

    public static void requireValidRequestReferences(
            List<DocumentReference> references) {
        if (references == null || references.contains(null)) {
            throw new IllegalArgumentException(
                    "workbench run attachment references must not be null"
                            + " or contain null");
        }
        if (references.size() > MAXIMUM_ATTACHMENTS) {
            throw invalidRequest(
                    "workbench run accepts at most eight attachments");
        }
        Set<DocumentReference> unique = new HashSet<DocumentReference>();
        for (DocumentReference reference : references) {
            if (!unique.add(reference)) {
                throw invalidRequest(
                        "workbench run attachment references must be unique");
            }
        }
    }

    public static List<VerifiedWorkbenchRunAttachment> immutableList(
            List<VerifiedWorkbenchRunAttachment> attachments) {
        if (attachments == null || attachments.contains(null)) {
            throw new IllegalArgumentException(
                    "verified workbench run attachments must not be null"
                            + " or contain null");
        }
        List<DocumentReference> references =
                new ArrayList<DocumentReference>(attachments.size());
        for (VerifiedWorkbenchRunAttachment attachment : attachments) {
            references.add(attachment.getDocumentReference());
        }
        requireValidRequestReferences(references);
        return Collections.unmodifiableList(
                new ArrayList<VerifiedWorkbenchRunAttachment>(attachments));
    }

    public static List<VerifiedWorkbenchRunAttachment> immutableListForScope(
            List<VerifiedWorkbenchRunAttachment> attachments,
            RepositoryScope repositoryScope) {
        if (repositoryScope == null) {
            throw new IllegalArgumentException(
                    "verified attachment repository scope is required");
        }
        List<VerifiedWorkbenchRunAttachment> immutable =
                immutableList(attachments);
        for (VerifiedWorkbenchRunAttachment attachment : immutable) {
            if (!repositoryScope.containsRepository(
                    attachment.getDocumentReference().getRepositoryKey())) {
                throw new IllegalArgumentException(
                        "verified attachment repository is outside frozen scope");
            }
        }
        return immutable;
    }

    private static WorkbenchDomainException invalidRequest(String message) {
        return new WorkbenchDomainException(
                WorkbenchErrorCode.REQUEST_INVALID, message);
    }
}
