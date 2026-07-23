package com.example.agentweb.app.harness;

/**
 * Harness 写命令缺失或使用非法幂等键。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class InvalidHarnessIdempotencyKeyException extends RuntimeException {

    public InvalidHarnessIdempotencyKeyException() {
        super("Idempotency-Key must contain 1 to 128 characters");
    }
}
