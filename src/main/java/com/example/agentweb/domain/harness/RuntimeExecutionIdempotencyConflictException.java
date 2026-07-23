package com.example.agentweb.domain.harness;

/**
 * 同一 Execution 幂等键被复用于不同 Run 或 Stage 语义。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class RuntimeExecutionIdempotencyConflictException
        extends IllegalHarnessTransitionException {

    public RuntimeExecutionIdempotencyConflictException(String runId,
                                                        HarnessStage existingStage,
                                                        HarnessStage requestedStage) {
        super("runtime execution idempotency key already belongs to " + runId + "/"
                + existingStage + ", requested stage=" + requestedStage);
    }
}
