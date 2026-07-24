package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Harness 阻断问题回答 DTO。
 *
 * @author alex
 * @since 2026-07-23
 */
@Getter
@Setter
public class HarnessAnswerRequest {

    @NotBlank
    @Size(max = 16384)
    private String answer;
}
