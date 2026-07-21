package com.example.agentweb.domain.verification;

/**
 * 验证熔断：轮次上限或同因重复失败命中，拒绝再发验证 run，人工介入后（resume/修复）再继续。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-04
 */
public class VerificationHaltedException extends RuntimeException {

    public VerificationHaltedException(String message) {
        super(message);
    }
}
