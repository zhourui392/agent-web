package com.example.agentweb.app.harness.port;

import com.example.agentweb.domain.harness.RuntimeExecutionSignal;
import lombok.Getter;

/**
 * Runtime Adapter 到应用回调入口的归一化非敏感事件。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class RuntimeEvent {

    private final String executionId;
    private final RuntimeExecutionSignal signal;
    private final String summary;

    public RuntimeEvent(String executionId, RuntimeExecutionSignal signal, String summary) {
        if (executionId == null || executionId.trim().isEmpty() || signal == null) {
            throw new IllegalArgumentException("runtime event identity and signal are required");
        }
        this.executionId = executionId.trim();
        this.signal = signal;
        this.summary = summary;
    }
}
