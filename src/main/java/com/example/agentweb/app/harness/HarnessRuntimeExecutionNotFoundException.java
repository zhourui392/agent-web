package com.example.agentweb.app.harness;

/**
 * 指定 Attempt 尚无 RuntimeExecution。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
public class HarnessRuntimeExecutionNotFoundException extends RuntimeException {

    public HarnessRuntimeExecutionNotFoundException(String runId, String stage, int attemptNumber) {
        super("RuntimeExecution not found: " + runId + "/" + stage + "/" + attemptNumber);
    }
}
