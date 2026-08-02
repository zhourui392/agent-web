package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.domain.workbench.WorkbenchRunAttachmentReference;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Workbench Run 附件的逻辑仓库相对引用和已观察内容 Hash。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
public class WorkbenchRunAttachmentRequest {

    @Size(max = 64)
    private String type;

    @Size(max = 512)
    private String repositoryKey;

    @Size(max = 4096)
    private String relativePath;

    @Size(max = 128)
    private String attachmentId;

    @Pattern(regexp = "(?i)^[0-9a-f]{64}$")
    private String contentHash;

    public WorkbenchRunAttachmentReference toReference() {
        String normalizedType = type == null || type.trim().isEmpty()
                ? "REPOSITORY_DOCUMENT"
                : type.trim().toUpperCase(java.util.Locale.ROOT);
        if ("REPOSITORY_DOCUMENT".equals(normalizedType)) {
            if (attachmentId != null && !attachmentId.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "repository attachment must not include attachmentId");
            }
            return WorkbenchRunAttachmentReference.repositoryDocument(
                    repositoryKey, relativePath, contentHash);
        }
        if ("UPLOADED_CONVERSATION".equals(normalizedType)) {
            if ((repositoryKey != null && !repositoryKey.trim().isEmpty())
                    || (relativePath != null && !relativePath.trim().isEmpty())) {
                throw new IllegalArgumentException(
                        "uploaded attachment must not include repository paths");
            }
            return WorkbenchRunAttachmentReference.uploadedConversation(
                    attachmentId, contentHash);
        }
        throw new IllegalArgumentException(
                "unsupported workbench run attachment type");
    }
}
