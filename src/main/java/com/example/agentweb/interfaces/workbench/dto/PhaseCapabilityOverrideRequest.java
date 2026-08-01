package com.example.agentweb.interfaces.workbench.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Phase Capability Override 的公开输入字段。
 *
 * @author alex
 * @since 2026-08-01
 */
@Getter
@Setter
@NoArgsConstructor
public final class PhaseCapabilityOverrideRequest {

    @NotNull
    @Size(max = 200)
    private List<@NotBlank @Size(max = 160) String> optionalSkillIds;

    @NotNull
    @Size(max = 200)
    private List<@NotBlank @Size(max = 160) String> optionalMcpServerIds;

    @NotNull
    @Size(max = 4000)
    private String additionalRule;

    @JsonAnySetter
    public void rejectInternalOrUnknownField(
            String field, Object ignoredValue) {
        throw new IllegalArgumentException(
                "unsupported capability override field: " + field);
    }
}
