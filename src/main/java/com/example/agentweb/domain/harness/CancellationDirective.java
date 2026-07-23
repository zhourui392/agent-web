package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * 取消意图持久化后，Application 是否需要终止外部 Runtime 的领域结果。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class CancellationDirective {

    private final boolean runtimeCancellationRequired;
    private final String executionId;

    private CancellationDirective(boolean runtimeCancellationRequired, String executionId) {
        this.runtimeCancellationRequired = runtimeCancellationRequired;
        this.executionId = executionId;
    }

    public static CancellationDirective completedWithoutRuntime() {
        return new CancellationDirective(false, null);
    }

    public static CancellationDirective cancelRuntime(String executionId) {
        return new CancellationDirective(true,
                DomainText.require(executionId, "cancel execution id", 128));
    }

    public boolean requiresRuntimeCancellation() {
        return runtimeCancellationRequired;
    }
}
