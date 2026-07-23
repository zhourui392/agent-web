package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Harness Run 创建请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessRunCreateRequest {

    @NotBlank
    private String title;
    @NotBlank
    private String workingDir;
    @NotBlank
    private String agentType;
    @NotBlank
    private String environment;
    private String definitionVersion;
}
