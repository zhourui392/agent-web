package com.example.agentweb.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 管理员更新用户建议请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-06-11
 */
@Getter
@Setter
public class UserSuggestionAdminUpdateRequest {

    @NotBlank
    private String status;

    @Size(max = 2000)
    private String adminReply;
}
