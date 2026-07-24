package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * 与已批准输入基线绑定的 local 部署动作 Approval DTO。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessDeploymentApprovalRequest {

    @NotBlank
    @Pattern(regexp = "[a-f0-9]{64}")
    private String inputBaselineHash;
    @NotBlank
    private String reason;
}
