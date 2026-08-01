package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.run.WorkbenchRunAttachmentReference;
import jakarta.validation.constraints.NotBlank;
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

    @NotBlank
    @Size(max = 512)
    private String repositoryKey;

    @NotBlank
    @Size(max = 4096)
    private String relativePath;

    @NotBlank
    @Pattern(regexp = "(?i)^[0-9a-f]{64}$")
    private String contentHash;

    public WorkbenchRunAttachmentReference toReference() {
        return WorkbenchRunAttachmentReference.of(
                repositoryKey, relativePath, contentHash);
    }
}
