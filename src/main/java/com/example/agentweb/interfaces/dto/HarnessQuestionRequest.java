package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 当前 Stage Attempt 请求补充输入的边界 DTO。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessQuestionRequest {

    @NotBlank
    @Size(max = 128)
    private String questionId;
    @NotBlank
    @Size(max = 4096)
    private String question;
    @NotNull
    private Boolean blocking;
}
