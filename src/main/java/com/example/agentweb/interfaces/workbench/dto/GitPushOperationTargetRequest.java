package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.operation.GitPushOperationTargetInput;
import com.example.agentweb.app.workbench.operation.HighImpactOperationTargetInput;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 非 force、非删除 ref 的 Git Push Target DTO。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class GitPushOperationTargetRequest
        implements HighImpactOperationTargetRequest {

    @NotBlank
    @Size(max = 128)
    private String repositoryKey;
    @NotBlank
    @Size(max = 128)
    private String remoteName;
    @NotBlank
    @Size(max = 512)
    private String localBranch;
    @NotBlank
    @Size(max = 1024)
    private String remoteRef;
    @NotBlank
    @Size(max = 64)
    private String expectedLocalHead;

    @Override
    public HighImpactOperationTargetInput toApplicationTarget() {
        return new GitPushOperationTargetInput(
                repositoryKey, remoteName, localBranch,
                remoteRef, expectedLocalHead);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported Git push target field: " + field);
    }
}
