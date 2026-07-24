package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Harness 阶段对话消息请求。
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
@Setter
public class HarnessConversationRequest {

    @NotBlank
    @Size(max = 20000)
    private String message;
}
