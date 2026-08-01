package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 接受上游 Handoff exact version/hash 的公开请求。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
@NoArgsConstructor
public final class AcceptHandoffReceptionRequest {

    @NotBlank
    @Size(max = 64)
    private String sourcePhase;

    @NotNull
    @PositiveOrZero
    private Long sourceVersion;

    @NotBlank
    @Pattern(regexp = "^[a-f0-9]{64}$")
    private String sourceHash;

    @JsonAnySetter
    public void rejectInternalOrUnknownField(
            String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported handoff reception field: " + field);
    }
}
