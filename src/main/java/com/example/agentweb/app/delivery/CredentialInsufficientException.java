package com.example.agentweb.app.delivery;

/**
 * SCM 凭证缺失或权限不足（个人未配置且系统默认账号不可用，或远端 403）。
 * Controller 层映射为引导文案（跳转 git 设置页配置个人凭证）。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class CredentialInsufficientException extends RuntimeException {

    public CredentialInsufficientException(String message) {
        super(message);
    }

    public CredentialInsufficientException(String message, Throwable cause) {
        super(message, cause);
    }
}
