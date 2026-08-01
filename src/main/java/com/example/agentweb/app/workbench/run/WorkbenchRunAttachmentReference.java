package com.example.agentweb.app.workbench.run;

import com.example.agentweb.domain.shared.DomainText;
import com.example.agentweb.domain.workbench.DocumentReference;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Workbench Run 客户端提交的附件声明；进入 Run 前必须重新读取并验证。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@EqualsAndHashCode
public final class WorkbenchRunAttachmentReference {

    private final DocumentReference documentReference;
    private final String contentHash;

    private WorkbenchRunAttachmentReference(
            DocumentReference documentReference, String contentHash) {
        if (documentReference == null) {
            throw new IllegalArgumentException(
                    "workbench run attachment document must not be null");
        }
        this.documentReference = documentReference;
        this.contentHash = DomainText.requireSha256(
                contentHash, "workbench run attachment content hash");
    }

    public static WorkbenchRunAttachmentReference of(
            String repositoryKey, String relativePath, String contentHash) {
        return new WorkbenchRunAttachmentReference(
                DocumentReference.of(repositoryKey, relativePath), contentHash);
    }
}
