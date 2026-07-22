package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * POST SSE 聊天请求，避免把用户消息和恢复标识放入 URL/访问日志。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Getter
@Setter
public class StreamMessageRequest {

    @NotBlank
    @Size(max = 131072)
    private String message;

    @Size(max = 256)
    private String resumeId;

    @Size(max = 64)
    private String env;

    private boolean recall = true;
}
