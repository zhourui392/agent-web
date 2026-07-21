package com.example.agentweb.interfaces.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 通用操作成功响应。
 * @author zhourui(V33215020)
 */
@Getter
@Setter
public class SuccessResponse {
    private boolean success;

    public SuccessResponse(boolean success) {
        this.success = success;
    }
}
