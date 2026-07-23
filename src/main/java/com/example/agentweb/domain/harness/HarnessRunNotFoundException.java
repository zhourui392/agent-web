package com.example.agentweb.domain.harness;

/**
 * Harness Run 不存在。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class HarnessRunNotFoundException extends RuntimeException {

    public HarnessRunNotFoundException(String runId) {
        super("harness run not found: " + runId);
    }
}
