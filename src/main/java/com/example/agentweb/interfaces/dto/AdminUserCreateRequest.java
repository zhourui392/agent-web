package com.example.agentweb.interfaces.dto;

import com.example.agentweb.domain.auth.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建用户请求。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-22
 */
@Data
public class AdminUserCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String username;

    @NotBlank
    @Size(min = 12, max = 256)
    private String password;

    @NotNull
    private UserRole role;
}
