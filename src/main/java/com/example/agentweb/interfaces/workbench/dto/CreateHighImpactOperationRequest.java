package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 固定 UI/API 发起的类型化高影响操作提案。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class CreateHighImpactOperationRequest {

    @NotBlank
    @Size(max = 128)
    private String sourceRunId;

    @NotBlank
    @Size(max = 64)
    private String phase;

    @NotBlank
    @Size(max = 2000)
    private String safeSummary;

    @Valid
    @NotNull
    private HighImpactOperationTargetRequest target;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported high-impact operation proposal field: " + field);
    }
}
