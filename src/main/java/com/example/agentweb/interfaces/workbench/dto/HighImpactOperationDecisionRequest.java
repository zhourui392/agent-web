package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Owner 对类型化高影响操作的显式决策请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class HighImpactOperationDecisionRequest {

    @NotBlank
    @Size(max = 32)
    private String decision;

    @NotBlank
    @Size(max = 2000)
    private String reason;

    @JsonAnySetter
    public void rejectInternalOrUnknownField(
            String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported high-impact operation decision field: " + field);
    }
}
