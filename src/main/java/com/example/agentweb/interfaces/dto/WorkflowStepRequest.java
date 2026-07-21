package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * 工作流步骤保存请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-12
 */
@Getter
@Setter
public class WorkflowStepRequest {

    @NotBlank
    private String name;
    @NotBlank
    private String promptTemplate;
    private long timeoutSeconds;
}
