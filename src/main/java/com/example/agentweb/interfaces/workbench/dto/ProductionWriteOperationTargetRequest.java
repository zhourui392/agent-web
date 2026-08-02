package com.example.agentweb.interfaces.workbench.dto;

import com.example.agentweb.app.workbench.operation.HighImpactOperationTargetInput;
import com.example.agentweb.app.workbench.operation.ProductionWriteOperationTargetInput;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * MVP 固定不可执行的生产写 Target DTO。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@NoArgsConstructor
public class ProductionWriteOperationTargetRequest
        implements HighImpactOperationTargetRequest {

    @NotBlank
    @Size(max = 128)
    private String environment;
    @NotBlank
    @Size(max = 1024)
    private String resourceReference;
    @NotBlank
    @Size(max = 64)
    private String expectedProductionStateHash;

    @Override
    public HighImpactOperationTargetInput toApplicationTarget() {
        return new ProductionWriteOperationTargetInput(
                environment, resourceReference, expectedProductionStateHash);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported production write target field: " + field);
    }
}
