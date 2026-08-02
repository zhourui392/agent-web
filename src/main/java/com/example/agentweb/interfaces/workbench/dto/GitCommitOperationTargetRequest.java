package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.operation.GitCommitOperationTargetInput;
import com.example.agentweb.app.workbench.operation.HighImpactOperationTargetInput;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Git Commit 类型化 Target DTO；只接受显式路径，不接受命令字符串。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class GitCommitOperationTargetRequest
        implements HighImpactOperationTargetRequest {

    @NotBlank
    @Size(max = 128)
    private String repositoryKey;
    @NotBlank
    @Size(max = 512)
    private String branch;
    @NotBlank
    @Size(max = 64)
    private String expectedHead;
    @NotBlank
    @Size(max = 64)
    private String expectedStateHash;
    @NotEmpty
    @Size(max = 1000)
    private List<@NotBlank @Size(max = 4096) String> includedPaths;
    @NotBlank
    @Size(max = 64)
    private String messageHash;
    @NotBlank
    @Size(max = 500)
    private String safeMessagePreview;

    @Override
    public HighImpactOperationTargetInput toApplicationTarget() {
        return new GitCommitOperationTargetInput(
                repositoryKey, branch, expectedHead, expectedStateHash,
                includedPaths, messageHash, safeMessagePreview);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported Git commit target field: " + field);
    }
}
