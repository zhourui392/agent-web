package com.example.agentweb.app.agentrun.port;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;

import java.util.Set;

/**
 * Infrastructure adapter for one or more agent identities.
 *
 * @author alex
 * @since 2026-07-29
 */
public interface AgentRuntime {

    Set<AgentType> supportedTypes();

    /** Provider-neutral execution entrypoint. */
    default RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        throw new UnsupportedOperationException(
                "runtime does not support the common execution port");
    }

    /** Stop a handle owned by this runtime. */
    default void requestStop(RuntimeHandle handle) {
        throw new UnsupportedOperationException(
                "runtime does not support the common execution port");
    }

    /** Observe a handle owned by this runtime. */
    default RuntimeObservation observe(RuntimeHandle handle) {
        return RuntimeObservation.notFound(handle);
    }
}
