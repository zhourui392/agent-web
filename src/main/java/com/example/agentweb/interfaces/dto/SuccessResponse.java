package com.example.agentweb.interfaces.dto;

/**
 * 通用操作成功响应。
 */
public class SuccessResponse {
    private boolean success;

    public SuccessResponse(boolean success) {
        this.success = success;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
