package com.example.agentweb.app.harness;

import com.example.agentweb.app.harness.port.AgentExecutionSpec;
import lombok.Getter;

/**
 * Prepare 事务提交后交给非事务 Launcher 的执行规格。
 *
 * @author zhourui(V33215020)
 * @since 2026-07-23
 */
@Getter
public final class PreparedHarnessExecution {

    private final AgentExecutionSpec spec;
    private final boolean duplicated;

    public PreparedHarnessExecution(AgentExecutionSpec spec) {
        this(spec, false);
    }

    public PreparedHarnessExecution(AgentExecutionSpec spec, boolean duplicated) {
        if (spec == null) {
            throw new IllegalArgumentException("prepared execution spec must not be null");
        }
        this.spec = spec;
        this.duplicated = duplicated;
    }
}
