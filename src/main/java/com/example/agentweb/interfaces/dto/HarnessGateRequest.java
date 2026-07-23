package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Harness 确定性 Gate 结果请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessGateRequest {

    @NotBlank
    private String rule;
    @NotNull
    private Boolean passed;
    private List<String> evidenceReferences;
    private String reason;
}
