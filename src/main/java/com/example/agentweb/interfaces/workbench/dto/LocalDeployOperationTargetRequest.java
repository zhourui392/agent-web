package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.operation.HighImpactOperationTargetInput;
import com.example.agentweb.app.workbench.operation.LocalDeployOperationTargetInput;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 只引用管理员模板且环境固定为 LOCAL 的部署 Target DTO。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class LocalDeployOperationTargetRequest
        implements HighImpactOperationTargetRequest {

    @NotBlank
    @Size(max = 128)
    private String templateId;
    @NotBlank
    @Size(max = 128)
    private String templateVersion;
    @NotBlank
    @Size(max = 64)
    private String templateHash;
    @NotEmpty
    @Size(max = 50)
    private List<@NotBlank @Size(max = 128) String> repositoryTargets;
    @NotNull
    private LocalEnvironment environment;
    @NotBlank
    @Size(max = 64)
    private String expectedWorkspaceStateHash;
    @NotBlank
    @Size(max = 2000)
    private String rollbackSummary;

    @Override
    public HighImpactOperationTargetInput toApplicationTarget() {
        return new LocalDeployOperationTargetInput(
                templateId, templateVersion, templateHash,
                repositoryTargets, expectedWorkspaceStateHash,
                rollbackSummary);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported local deploy target field: " + field);
    }

    public enum LocalEnvironment {
        LOCAL
    }
}
