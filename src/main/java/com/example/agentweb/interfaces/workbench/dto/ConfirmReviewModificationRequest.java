package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 显式确认 Review MODIFY 的公开 exact Opinion 证明。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
@NoArgsConstructor
public final class ConfirmReviewModificationRequest {

    @NotNull
    @Positive
    private Long opinionVersion;

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String opinionHash;

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported review confirmation field: " + field);
    }
}
