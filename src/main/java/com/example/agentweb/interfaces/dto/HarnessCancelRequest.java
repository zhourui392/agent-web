package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Harness Run 取消请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessCancelRequest {

    @NotBlank
    private String reason;
}
