package com.example.agentweb.domain.harness;

/**
 * 同一创建者重复使用 Harness Run 幂等键且请求不一致。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class DuplicateHarnessRunException extends RuntimeException {

    public DuplicateHarnessRunException(String createdBy, String idempotencyKey) {
        super("duplicate harness run submission for " + createdBy + ": " + idempotencyKey);
    }
}
