package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Harness 模拟 Artifact 上传请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessArtifactRequest {

    @NotBlank
    private String artifactType;
    @NotNull
    @Size(max = 1048576)
    private String content;
    @NotBlank
    private String contentType;
    @NotBlank
    private String classification;
}
