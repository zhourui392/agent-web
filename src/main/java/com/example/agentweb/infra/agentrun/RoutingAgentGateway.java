package com.example.agentweb.infra.agentrun;

import com.example.agentweb.app.agentrun.port.AgentRuntime;
import com.example.agentweb.app.runtime.port.AgentExecutionGateway;
import com.example.agentweb.app.runtime.port.AgentExecutionPlan;
import com.example.agentweb.app.runtime.port.RuntimeEventSink;
import com.example.agentweb.app.runtime.port.RuntimeHandle;
import com.example.agentweb.app.runtime.port.RuntimeObservation;
import com.example.agentweb.app.runtime.port.RuntimeState;
import com.example.agentweb.domain.agentrun.AgentRuntimeUnavailableException;
import com.example.agentweb.domain.shared.AgentType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes provider-neutral plans and handles to the Runtime that owns them.
 *
 * <p>Legacy Invocation execution is intentionally absent. The explicit
 * rollback AgentGateway is implemented by {@link CliAgentRuntime}; this
 * router only exposes the common asynchronous lifecycle port.</p>
 *
 * @author alex
 * @since 2026-07-29
 */
public class RoutingAgentGateway implements AgentExecutionGateway {

    private final Map<AgentType, AgentRuntime> runtimes;
    private final Map<String, AgentRuntime> activeRuntimeHandles =
            new ConcurrentHashMap<String, AgentRuntime>();
    private final Map<String, RuntimeObservation> terminalRuntimeHandles =
            new ConcurrentHashMap<String, RuntimeObservation>();
    private final Map<String, RuntimeHandle> pendingStops =
            new ConcurrentHashMap<String, RuntimeHandle>();

    public RoutingAgentGateway(List<AgentRuntime> runtimeBeans) {
        this.runtimes = index(runtimeBeans);
    }

    @Override
    public RuntimeHandle start(AgentExecutionPlan plan, RuntimeEventSink sink) {
        if (plan == null || sink == null) {
            throw new IllegalArgumentException("runtime plan and sink are required");
        }
        AgentRuntime runtime = requireRuntime(
                plan.getRuntimeSelection().getAgentType());
        RuntimeHandle handle = Objects.requireNonNull(
                runtime.start(plan, sink), "runtime returned no handle");
        AgentRuntime previous = activeRuntimeHandles.putIfAbsent(
                handle.getHandleId(), runtime);
        if (previous != null) {
            throw new IllegalStateException("runtime handle is already routed");
        }
        RuntimeHandle pending = pendingStops.remove(handle.getExecutionId());
        if (pending != null) {
            runtime.requestStop(handle);
        }
        return handle;
    }

    @Override
    public void requestStop(RuntimeHandle handle) {
        if (handle == null) {
            return;
        }
        AgentRuntime runtime = activeRuntimeHandles.get(handle.getHandleId());
        if (runtime == null) {
            RuntimeObservation terminal = terminalRuntimeHandles.get(handle.getHandleId());
            if (terminal != null && terminal.getState() == RuntimeState.TERMINATED) {
                return;
            }
            pendingStops.putIfAbsent(handle.getExecutionId(), handle);
            return;
        }
        runtime.requestStop(handle);
    }

    @Override
    public RuntimeObservation observe(RuntimeHandle handle) {
        if (handle == null) {
            throw new IllegalArgumentException("runtime handle is required");
        }
        AgentRuntime runtime = activeRuntimeHandles.get(handle.getHandleId());
        if (runtime != null) {
            RuntimeObservation observation = runtime.observe(handle);
            if (observation.getState() == RuntimeState.TERMINATED) {
                activeRuntimeHandles.remove(handle.getHandleId(), runtime);
                terminalRuntimeHandles.put(handle.getHandleId(), observation);
                pendingStops.remove(handle.getExecutionId());
            }
            return observation;
        }
        RuntimeObservation terminal = terminalRuntimeHandles.get(handle.getHandleId());
        return terminal == null ? RuntimeObservation.notFound(handle) : terminal;
    }

    private Map<AgentType, AgentRuntime> index(List<AgentRuntime> beans) {
        if (beans == null) {
            throw new IllegalArgumentException("agent runtimes are required");
        }
        EnumMap<AgentType, AgentRuntime> result =
                new EnumMap<AgentType, AgentRuntime>(AgentType.class);
        for (AgentRuntime runtime : beans) {
            if (runtime == null) {
                throw new IllegalArgumentException("agent runtimes must be complete");
            }
            for (AgentType type : runtime.supportedTypes()) {
                if (result.put(type, runtime) != null) {
                    throw new IllegalStateException("Duplicate agent runtime: " + type);
                }
            }
        }
        return result;
    }

    private AgentRuntime requireRuntime(AgentType type) {
        AgentRuntime runtime = runtimes.get(type);
        if (runtime == null) {
            throw new AgentRuntimeUnavailableException(
                    "AGENT_RUNTIME_UNAVAILABLE", "Agent runtime is unavailable: " + type);
        }
        return runtime;
    }
}
