package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户提交建议请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
@Setter
public class UserSuggestionRequest {

    @Size(max = 80)
    private String title;

    @NotBlank
    @Size(max = 2000)
    private String content;

    @Size(max = 120)
    private String contact;
}
