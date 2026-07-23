package com.example.agentweb.domain.harness;

/**
 * Harness 聚合拒绝非法业务状态转换。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class IllegalHarnessTransitionException extends IllegalStateException {

    public IllegalHarnessTransitionException(String message) {
        super(message);
    }
}
