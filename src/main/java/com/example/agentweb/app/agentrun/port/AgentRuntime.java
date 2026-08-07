package com.example.agentweb.app.agentrun.port;

import com.example.agentweb.domain.shared.AgentType;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Infrastructure adapter for one or more agent identities.
 *
 * @author alex
 * @since 2026-07-29
 */
public interface AgentRuntime {

    Set<AgentType> supportedTypes();

    HistoryDeliveryMode historyDeliveryMode();

    void run(AgentRunInvocation invocation, Consumer<String> onChunk,
             Consumer<AgentExecutionResult> onComplete)
            throws IOException, InterruptedException;

    void stop(String runId);

    String extractResumeId(AgentType type, String rawLine);

    List<String> normalizeChunk(AgentType type, String rawLine);

    /** New provider-neutral execution entrypoint. */
    default RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        throw new UnsupportedOperationException(
                "runtime does not support the common execution port");
    }

    /** Stop a handle owned by this runtime. */
    default void requestStop(RuntimeHandle handle) {
        if (handle != null) {
            stop(handle.getExecutionId());
        }
    }

    /** Observe a handle owned by this runtime. */
    default RuntimeObservation observe(RuntimeHandle handle) {
        return RuntimeObservation.notFound(handle);
    }
}
