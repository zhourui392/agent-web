package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * local 部署执行请求。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessDeploymentStartRequest {

    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9_-]{1,128}")
    private String templateId;

    @NotBlank
    @Pattern(regexp = "[a-fA-F0-9]{64}")
    private String approvedInputBaselineHash;
}
