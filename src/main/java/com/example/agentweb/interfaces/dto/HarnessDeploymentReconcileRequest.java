package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 部署人工对账失败确认。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessDeploymentReconcileRequest {

    @NotBlank
    @Size(max = 1000)
    private String reason;
}
