package com.example.agentweb.domain.workbench.context;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.DocumentReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * 单次 Stage Run 冻结的全局上下文文档元数据。
 *
 * @author alex
 * @since 2026-08-05
 */
@Getter
@EqualsAndHashCode
public final class WorkbenchContextDocumentSnapshot {

    private final String contextDocumentIdentifier;
    private final String sourceStageInstanceIdentifier;
    private final String sourceRunIdentifier;
    private final String documentName;
    private final String briefDescription;
    private final DocumentReference documentReference;
    private final String publishedContentHash;
    private final WorkbenchContextDocumentContentState contentState;

    public WorkbenchContextDocumentSnapshot(
            String contextDocumentIdentifier,
            String sourceStageInstanceIdentifier,
            String sourceRunIdentifier,
            String documentName, String briefDescription,
            DocumentReference documentReference,
            String publishedContentHash,
            WorkbenchContextDocumentContentState contentState) {
        this.contextDocumentIdentifier = DomainText.require(
                contextDocumentIdentifier,
                "Context Document identifier", 128);
        this.sourceStageInstanceIdentifier = DomainText.require(
                sourceStageInstanceIdentifier,
                "Context Document source Stage identifier", 128);
        this.sourceRunIdentifier = optional(
                sourceRunIdentifier, "Context Document source Run identifier", 128);
        this.documentName = DomainText.require(
                documentName, "Context Document name", 256);
        this.briefDescription = DomainText.require(
                briefDescription, "Context Document description", 2000);
        if (documentReference == null || contentState == null) {
            throw new IllegalArgumentException(
                    "Context Document reference and content state are required");
        }
        this.documentReference = documentReference;
        this.publishedContentHash = DomainText.requireSha256(
                publishedContentHash, "Context Document published content Hash");
        this.contentState = contentState;
    }

    private static String optional(
            String value, String name, int maximumLength) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return DomainText.require(value, name, maximumLength);
    }
}
