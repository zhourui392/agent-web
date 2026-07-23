package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 绑定 Artifact 基线 Hash 的批准或拒绝请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessApprovalRequest {

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String artifactBaselineHash;
    @NotBlank
    private String reason;
}
