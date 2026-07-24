package com.example.agentweb.domain.harness;

import lombok.Getter;

/**
 * RuntimeExecution 终态向 HarnessRun 投影的不可变领域结果。
 *
 * <p>Run 只消费 Execution Reference 和终态语义，不跨聚合读取 RuntimeExecution 内部状态。</p>
 *
 * @author alex
 * @since 2026-07-24
 */
@Getter
public final class RuntimeExecutionOutcome {

    private final ExecutionReference reference;
    private final RuntimeExecutionStatus status;
    private final String terminationReason;

    RuntimeExecutionOutcome(ExecutionReference reference, RuntimeExecutionStatus status,
                            String terminationReason) {
        if (reference == null || status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("runtime execution outcome must be terminal");
        }
        this.reference = reference;
        this.status = status;
        this.terminationReason = terminationReason;
    }

    public boolean producesArtifacts() {
        return status == RuntimeExecutionStatus.SUCCEEDED;
    }
}
