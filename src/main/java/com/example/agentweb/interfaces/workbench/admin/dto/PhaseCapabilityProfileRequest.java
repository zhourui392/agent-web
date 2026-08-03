package com.example.agentweb.interfaces.workbench.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 管理后台更新 Phase Capability Profile 的请求体。
 *
 * @author alex
 * @since 2026-08-02
 */
@Getter
@Setter
public class PhaseCapabilityProfileRequest {

    @NotEmpty(message = "capabilities must not be empty")
    @Valid
    private List<CapabilityReferenceInput> capabilities;

    @Getter
    @Setter
    public static class CapabilityReferenceInput {
        @NotBlank(message = "capability id must not be blank")
        private String id;

        @NotNull(message = "capability type must not be null")
        private String type;

        private boolean required;
    }
}
